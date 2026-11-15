# Load test 01: Baseline

**Date:** 2026-11-15
**Purpose:** How the deployed system behaves under normal buying, before any tuning. Every later test is compared with this one.

## Setup

| | |
|---|---|
| **Server** | Hetzner CX23: 2 shared vCPUs, 4 GB RAM, Germany |
| **Running on it** | Spring Boot app (heap capped at 512 MB), PostgreSQL 17, fake payment provider, Caddy (HTTPS), Prometheus, Grafana |
| **Load generator** | Gatling 3.14, run from a laptop in the Netherlands |
| **Network** | About **30 ms** round trip from the load generator to the server. Every response time below includes it |
| **Hold strategy** | Conditional update (default) |
| **Event** | 1,000 seats, one section |
| **Users** | 500 pre-registered customers, logged in before the test (tokens fed from CSV), so login and bcrypt aren't part of the measurement |

**Command**

```bash
./gradlew -p load-tests gatlingRun --simulation ticketing.BuyerSimulation \
  -DbaseUrl=https://api.2-31-202-145.sslip.io \
  -DpspUrl=https://pay.2-31-202-145.sslip.io \
  -DeventId=2 -DusersPerSec=2 -DdurationSeconds=120
```

## Scenario

Each simulated fan:

1. Lists available seats (page of 100), thinks for 0.5 to 2 s, and picks 2 at random.
2. Holds them. On a `409` (someone else was faster) they look again, up to 3 attempts.
3. Thinks for 1 to 3 s, then checks out.
4. Thinks for 2 to 5 s, then at the payment provider: **80%** approve, **10%** decline, **10%** walk away.
5. If they approved or declined, refreshes the order every 0.5 s until it's no longer `PENDING`.

Fans arrive at a constant **2 per second** for **2 minutes** (open model): about 240 fans.

## Results

**1,188 requests, 0 failed.** About 9 requests per second overall.

| Request | Count | p50 | p75 | p95 | p99 | Max |
|---|---|---|---|---|---|---|
| Available seats | 259 | 85 ms | 90 ms | 113 ms | 237 ms | 1,002 ms |
| Hold seats | 259 | 27 ms | 30 ms | 39 ms | 533 ms | 630 ms |
| Check out | 240 | 33 ms | 36 ms | 45 ms | 140 ms | 516 ms |
| Provider: approve | 195 | 63 ms | 66 ms | 89 ms | 625 ms | 724 ms |
| Check order | 215 | 22 ms | 25 ms | 35 ms | 633 ms | 1,074 ms |
| Provider: decline | 20 | 64 ms | 67 ms | 1,270 ms | 1,270 ms | 1,270 ms |
| **All requests** | **1,188** | **34 ms** | **67 ms** | **95 ms** | **517 ms** | **1,270 ms** |

**Server side (Grafana, p95 inside the app)**

| Endpoint | At the start | After about 2 minutes |
|---|---|---|
| `/events/{eventId}/seats` (available seats) | ~25 ms | **~6 ms** |
| `/events/{eventId}/holds` (hold seats) | ~25 ms | ~9 ms |
| `/webhooks/payments` | ~12 ms | ~7 to 11 ms |
| `/orders/{orderId}` (check order) | ~5 ms | ~2 to 3 ms |
| `/holds/{holdId}/checkout` | ~28 ms | **16 to 28 ms**, the slowest: it includes the call to the payment provider |

Every endpoint started slower and improved over the first two minutes, as the JVM optimised the code being run (JIT warm-up). CPU peaked at **15%**.

![Server-side response times during the baseline](01-baseline-grafana-response-times.png)

**Outcomes in the database**

| Order status | Count | Share | Expected from the scenario |
|---|---|---|---|
| Confirmed | 195 | 81% | 80% |
| Failed (declined) | 20 | 8% | 10% |
| Pending (walked away) | 25 | 10% | 10% |

Every fan got seats and placed an order (240 orders). The pending orders are expected to become `EXPIRED` once their 15-minute payment deadline passes.

**Consistency check** (`load-tests/check-consistency.sh`): all five checks returned **0**. No held seat on an inactive hold, no sold seat without a ticket, no ticket on an unsold seat, no confirmed order without tickets, no ticket on an unconfirmed order.

## Observations

1. **The server is far from its limits at this rate.** 15% CPU, and once warmed up, most endpoints are answered in 3 to 10 ms inside the app.
2. **Most of the client-side time is network, not the app.** Gatling sees 22 to 113 ms where the app itself needs 3 to 10 ms. The ~30 ms round trip accounts for most of the difference.
3. **"Available seats" is the slowest request for the client, but one of the fastest for the server.** Gatling: p95 **113 ms**. Inside the app: **~6 ms**. So about 100 ms is spent outside the app. The likely cause is response size: a page of 100 seats is much larger than any other response, and larger responses need several round trips over a 30 ms connection, because TCP increases its speed gradually. The database and the app's code are **not** the problem here. Candidates: response compression, a smaller page size, leaner seat objects. To confirm with measured response sizes before changing anything.
4. **Checkout is the slowest endpoint inside the app** (16 to 28 ms), because it waits for the payment provider before answering. Expected, and a likely place to look under heavier load.
5. **Warm-up is clearly visible.** Every endpoint started around 20 to 25 ms and settled over about two minutes. It also explains the occasional client-side outliers up to about 1.3 s (p99 and max): early requests, plus new HTTPS connections. **Future tests should run a short warm-up load before measuring**, so results show steady-state behaviour.

## Next

- Stress test: increase the arrival rate step by step until response times or errors rise, and identify which resource gives out first (CPU, database connections, payment provider, or network).
- On-sale rush on the server (`-DpickFromFirst=10`), to measure contention: share of `409`s and how hold latency behaves under it.
