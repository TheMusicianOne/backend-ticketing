# Load test experiments

**Status:** Concluded for now (2026-11-15). Five runs, one application change, two open questions.

Every change is measured against a reference run, under the same conditions, **one change at a time**. Each row in the log links to its full report. The method and the reasoning behind it are in [decision note 0005](../decisions/0005-performance-and-load-testing.md).

## Summary

| Finding | Evidence |
|---|---|
| At low load the server is far from its limits: most endpoints answer in 3 to 10 ms inside the app | [01](01-baseline.md), [02](02-stress-test-20.md) |
| Slow first requests for clients come from opening a new HTTPS connection (~65 ms over a 30 ms link), not from the app, the database or response size | [01](01-baseline.md), measured phase by phase with `curl` |
| With a realistic scenario, **the knee is at about 25 fans per second**, and **the database connection pool saturates first** | [04](04-stress-test-fixed-scenario.md) |
| The cause: **no index matched "this event's available seats"**, so the seat count scanned the whole table and pages walked the primary key past tens of thousands of rows | `EXPLAIN ANALYZE` in [04](04-stress-test-fixed-scenario.md) |
| **One composite index** made the first page **65× faster**, cut database work per look from ~29 to ~12 ms, **cut timeouts by 90%** and let the server complete **34% more requests** under the same load. The knee moved from ~25 to ~30 fans per second | [05](05-index-event-status-id.md) |
| After the index, the next bottleneck is CPU shared between the app, PostgreSQL, Caddy and the payment provider; the app itself used only 20 to 35% of it | [05](05-index-event-status-id.md) |
| **Correctness held under every overload.** In all five runs, including 17% failed requests and minute-long timeouts, every seat, hold, order and ticket agreed with each other | `check-consistency.sh` after every run |
| Two flaws in the test scenario itself were found and fixed: every fan saw the same 100 seats, and fans continued after a failed checkout | [03](03-stress-test-60.md) |

## Log

| # | Date | Change | Compared with | Load | Client response times | Server side (Grafana) | CPU peak | Failures | Consistency | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| [01](01-baseline.md) | 2026-11-15 | None: baseline, conditional-update holds | (reference) | 2 fans/s, 120 s | p95 95 ms (all) / 113 seats / 39 hold / 45 checkout | p95 3-10 ms, checkout 16-28 ms | 15% | 0 of 1,188 | All 0 | Reference point. Server far from its limits |
| [02](02-stress-test-20.md) | 2026-11-15 | None: stepped stress test, with a 60 s warm-up | 01 | Steps 4 → 20 fans/s, 60 s each | p95 93 ms (all) | p95 ≤ 30 ms; max 4 of 10 DB connections | 50% | 0 of 24,439 | All 0 | No knee yet |
| [03](03-stress-test-60.md) | 2026-11-15 | None: stress test past the knee | 02 | Steps 10 → 60 fans/s, 60 s each | p95 24.5 s (all) | Pool exhausted: 10 active, ~190 waiting | 100% | 256 real (`503` at checkout) of 87,736, plus 2,541 test artefacts | All 0 | Knee crossed. **Scenario flaws found** |
| [04](04-stress-test-fixed-scenario.md) | 2026-11-15 | Scenario fixed: random seat page, stop after a failed checkout. **New reference** | 03 | Steps 10 → 50 fans/s by 5, 60 s each | p50 7.6 s, p95 60 s (timeouts) | Pool saturated first, from ~25 fans/s | 100% | 13,134 of 75,682 (17%) | All 0 | **Knee at ~25 fans/s.** Cause: seat-listing queries without a matching index |
| [05](05-index-event-status-id.md) | 2026-11-15 | **Index `seats (event_id, status, id)`** (`V8`) | 04 | Same as 04 (reused event 5) | p50 5.4 s, p95 49.7 s | Knee ~30 fans/s; pool still saturated (150-180 pending) at 35-40 fans/s; checkout up to ~12 s | 100% (app only 20-35%) | 9,155 of 101,497 (9%) | All 0 | **Timeouts −90%, +34% requests, first page 65× faster.** Bottleneck moved to shared CPU and the payment provider |

## Answered questions

| Question | Answer | How it was measured |
|---|---|---|
| Why is "available seats" slow for clients (p95 113 ms) when the app answers it in ~6 ms? | It is each fan's **first request**, so it pays for a new HTTPS connection: DNS 12 ms, TCP 27 ms, TLS 26 ms, then one round trip. Not the database, the app, or response size (11 KB per 100 seats, received ~10 ms after the first byte). The provider's "approve" is slow for the same reason | `curl -w` timing of each phase on a fresh connection; response sizes at `size=100` and `size=10` |
| Where is the knee, and what gives out first? | **About 25 fans per second; the database connection pool**, then CPU. Caused by the seat-listing queries | Test 04, Grafana watched live; `EXPLAIN ANALYZE` of the count, first page and deep page |
| How much does an index on `seats (event_id, status, id)` help? | Same-data comparison: count 8.5 → 4.5 ms, first page 8.0 → 0.12 ms, `OFFSET 10000` 17.6 → 4.8 ms. Under load: timeouts −90%, +34% requests, knee from ~25 to ~30 fans/s | Test 05; index dropped inside a rolled-back transaction for the "without" numbers |

## Open questions

These were planned but not run. Each needs one more session.

| Question | How to answer it |
|---|---|
| Which process uses the ~75% of CPU the app doesn't: PostgreSQL, the payment provider, or Caddy? | `docker stats` on the server during a steady 35 fans/s run |
| How do the three hold strategies (conditional update, optimistic, pessimistic) compare under real contention? | On-sale rush (`-DpickFromFirst=10`) once per strategy (`app.holds.strategy`), comparing hold latency, share of `409`s, CPU and connection-pool waits |

## Candidate changes

| Change | Status / expected effect |
|---|---|
| Index `seats (event_id, status, id)` | **Done** in test 05 |
| Avoid the second count per look (`Slice` instead of `Page` for the seat listing) | Next. The counts are ~9 of the ~12 ms of database work per look, and one of them is redundant |
| Keyset pagination (`id > last seen`) | Deep pages would cost the same as the first |
| Payment provider on its own server | Removes an artefact of the single-server setup: a real provider never shares the merchant's CPU |
| Connection pool size | Only after the queries are cheap: with 2 vCPUs, more connections for expensive queries mainly adds CPU contention |
| Rate limiting, or a short wait for a connection | Answer "busy, try again" quickly instead of slowly failing everyone under overload |
| Virtual threads | Threads blocked waiting on the database or the provider |
| Compression in Caddy, smaller seat pages | Low priority: response size was ruled out as a cause |

## How to compare fairly

- Same server, scenario, arrival rate and duration as the run it's compared with.
- A fresh event with the same number of seats for every run, so earlier runs' sold seats don't change the results. (Test 05 broke this rule by reusing event 5; its report says so.)
- Fresh tokens (`seed-users.sh`) just before the run, since they expire after an hour.
- Payment provider chaos settings all at 0, unless the experiment is about them.
- A warm-up period before measuring, so results show a warmed-up JVM.
- Nothing pushed to `main` during a run, since a deploy can restart the app.
- After every run: `load-tests/check-consistency.sh <event-id>` must report all zeros.
- Note anything unusual: other load on the laptop, a deploy, network problems.

## Reproducing a run

```bash
# 1. Users and fresh tokens (on the load-generating machine)
./load-tests/seed-users.sh https://api.<host> 3000 10 tokens-server.csv

# 2. The run (stepped stress test, as in tests 04 and 05)
./gradlew -p load-tests gatlingRun --simulation ticketing.BuyerSimulation \
  -DbaseUrl=https://api.<host> -DpspUrl=https://pay.<host> \
  -DtokensFile=tokens-server.csv -DeventId=<event-id> \
  -DwarmupSeconds=60 -Dsteps=9 -DstartUsersPerSec=10 -DstepUsersPerSec=5 -DstepSeconds=60

# 3. Correctness (on the server)
./load-tests/check-consistency.sh <event-id>
```
