# 0005: Performance and load testing

**Status:** Accepted
**Date:** 2026-11-15
**Milestones:** 7 (Client simulator) and 8 (Performance fixes)

## Context

A ticket sale is a burst: thousands of fans arrive at once and compete for the same seats. Unit and race tests prove the logic is correct; they don't show **how much load the deployed system takes, what breaks first, or whether it stays correct while breaking**.

The goal was one honest, measured story: find the limit, find its cause, change one thing, and measure again. The results are in [`docs/load-tests`](../load-tests/EXPERIMENTS.md).

---

## 1. Gatling as the load generator

**Options**

| Option | Notes |
|---|---|
| JMeter | Widely used, but scenarios are built in a GUI or XML, which is hard to review |
| k6 | Good, but scenarios are in JavaScript |
| **Gatling, Java DSL (chosen)** | Scenarios are code in the same language as the app, versioned and reviewable, with detailed HTML reports |

The load tests live in their own Gradle project (`load-tests/`), so Gatling never becomes a dependency of the app.

**Open workload model.** Fans *arrive* at a rate (`constantUsersPerSec`, `incrementUsersPerSec`), independently of whether earlier fans have finished. That matches a public on-sale: new visitors don't wait for others. A closed model (a fixed number of users looping) would hide overload, because a slow server would automatically receive fewer requests.

---

## 2. A realistic scenario, and the flaws found in it

Each simulated fan:

1. Asks how many seats are available, opens a **random page** of 100, thinks for 0.5 to 2 s, and picks 2 seats.
2. Holds them. A `409` is not a failure: someone else was faster. The fan looks again, up to 3 times.
3. Checks out, with its own `Idempotency-Key`.
4. At the payment provider: **80%** approve, **10%** decline, **10%** walk away.
5. If they paid or declined, refreshes the order until it's decided, which also measures how fast webhooks confirm orders.
6. Stops if any step failed.

A separate parameter (`-DpickFromFirst=10`) turns the same scenario into an **on-sale rush**, where everyone goes for the first 10 seats.

**Two flaws were found by reading the results**, not the code (load test 03):

- Every fan originally saw the **same first page** of seats. At high rates almost everyone collided: 37,174 hold attempts led to only 4,752 checkouts. Fixed with a random page.
- Fans **carried on after a failed checkout**, producing 2,541 errors for orders that didn't exist. Fixed with `exitHereIfFailed()`.

Lesson: a load test is itself code that needs checking. Results that look strange are worth explaining before they are believed.

---

## 3. Users logged in before the test

Login runs bcrypt, which is deliberately slow. If every virtual user registered or logged in, the test would mostly measure password hashing. Instead, `seed-users.sh` creates the users and logs them in beforehand, and Gatling feeds each virtual user a row of `email,token` from a CSV.

**Trade-off:** tokens expire after an hour, so they are seeded just before each run. Login performance would need its own, separate test.

---

## 4. Correctness checked after every run

Fast but wrong is worse than slow. After every run, `check-consistency.sh` runs five SQL checks that must all return 0:

| Check | Would catch |
|---|---|
| Held seat whose hold is no longer active | Seats stuck forever after a release or expiry |
| Sold seat without a ticket | A customer paying without a ticket |
| Ticket for a seat that isn't sold | Effectively double-booking |
| Confirmed order without tickets | A confirmation that stopped halfway |
| Ticket on an order that isn't confirmed | Tickets for failed or expired orders |

Checks for situations the schema already makes impossible (a seat in two holds, two tickets for one seat) were deliberately left out: they can never fail, so they prove nothing.

**Result: all zeros after all five runs**, including runs with 17% failed requests and requests timing out after a minute.

---

## 5. The load generator on a laptop

**Options:** a temporary load-generating server in the same data center, or the laptop.

**Chosen: the laptop**, to keep costs at zero. To keep results honest:

- The round trip to the server was measured (about **30 ms**) and is stated in every report.
- **Server-side times come from Grafana**, measured inside the app, so network time can be separated from application time.
- The laptop was fast enough to generate the load the 2-vCPU server could handle.

**Trade-off:** client-side numbers include a home network. A load generator next to the server would give cleaner and more repeatable client-side numbers.

---

## 6. Method: one change at a time

```text
baseline → stepped stress test → find the knee → find the cause → change ONE thing → same test again
```

- A **warm-up period** (1 fan per second for 60 s) before measuring, because the JVM's JIT optimisation made early requests 2 to 4 times slower.
- **Stepped load** (levels held for 60 s each) so each level's metrics settle and can be read.
- Every run is recorded with its exact command, so it can be repeated.

---

## 7. The first fix: an index matching the query

**The finding (load test 04):** the knee was at about **25 fans per second**, and the **database connection pool** saturated first. `EXPLAIN ANALYZE` showed why: no index matched "this event's available seats". The count read every seat in the database (a sequential scan of 81,000 rows), and pages walked the primary key past tens of thousands of non-matching rows. Each fan's look cost roughly 29 ms of database work.

**Options**

| Option | Assessment |
|---|---|
| A bigger connection pool | Treats the symptom. With 2 vCPUs, more connections running expensive queries mostly adds CPU contention |
| Caching seat counts | Effective, but adds invalidation logic, for a number that changes constantly during a sale |
| Keyset pagination | Makes deep pages cheap, but doesn't fix the count, and changes the API |
| A partial index (`WHERE status = 'AVAILABLE'`) | Smaller, but only serves one status |
| **`CREATE INDEX ... ON seats (event_id, status, id)` (chosen)** | One migration, no code change, and it matches the count, the first page and deep pages |

**Why the column order matters:** the index is sorted by event, then status, then ID, like a phone book sorted by city, then street, then number. "This event's available seats, in ID order" is then one contiguous, already-sorted range.

**Measured on the same data** (the "without" numbers taken by dropping the index inside a transaction and rolling back):

| Query | Without | With |
|---|---|---|
| Count of available seats | 8.5 ms | 4.5 ms |
| First page | 8.0 ms | **0.12 ms (65×)** |
| Page at `OFFSET 10000` | 17.6 ms | 4.8 ms |

**Under load (test 05 against test 04):** timeouts **−90%**, **+34%** completed requests, failures from 17% to 9%, and the knee moved from about 25 to about 30 fans per second.

**What it didn't fix:** the bottleneck moved. The whole server's CPU reached 100% while the app used only 20 to 35%, and checkout failed while waiting on the payment provider, which shares the same CPU.

---

## 8. Where we stopped, and why

The remaining improvements were judged by value against effort:

| Next step | Why not done yet |
|---|---|
| Measure CPU per container (`docker stats`) | Quick; needed to confirm where the shared CPU goes. Planned next |
| Compare the three hold strategies under an on-sale rush | The original question of milestone 8, and the natural follow-up to [0002](0002-seat-holds-and-concurrency.md). Planned next |
| Remove the redundant second count (`Slice` instead of `Page`) | The counts are now ~9 of ~12 ms per look; a solid but incremental gain |
| Move the payment provider to its own server | Removes a setup artefact rather than improving the app |
| Keyset pagination, virtual threads, rate limiting | Larger changes with diminishing returns for this project |

---

## Known limitations of the measurements

| Limitation | Effect |
|---|---|
| Mostly one run per configuration | Shared vCPUs vary between runs; small differences shouldn't be over-read |
| Load generated from a home connection (~30 ms) | Client-side numbers include the network; server-side numbers come from Grafana |
| Payment provider and monitoring on the same server as the app | They compete for the CPU, which a real deployment wouldn't do |
| Test 05 reused test 04's event instead of a fresh one | It started with ~26,000 instead of 40,000 available seats; documented in its report |
| Metrics gaps during the worst overload | The most extreme moments are partly missing from Grafana |
| CPU per container and the hold-strategy comparison not measured yet | See the open questions in [EXPERIMENTS.md](../load-tests/EXPERIMENTS.md) |
