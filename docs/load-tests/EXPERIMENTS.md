# Load test experiments

Every change is measured against the baseline, under the same conditions, **one change at a time**. Each row links to its full report.

## How to compare fairly

- Same server, same scenario, same arrival rate and duration as the run it's compared with.
- A fresh event with the same number of seats for every run, so earlier runs' sold seats don't change the results.
- Fresh tokens (`seed-users.sh`), since they expire after an hour.
- Chaos settings on the payment provider all at 0, unless the experiment is about them.
- Nothing pushed to `main` during a run, since a deploy restarts the app.
- After every run: `load-tests/check-consistency.sh <event-id>` must report all zeros.
- Note anything unusual (another load on the laptop, a deploy, network problems).

## Log

| # | Date | Change | Compared with | Load | Client p95: all / seats / hold / checkout | Server p95 (Grafana) | CPU peak | Failures | Consistency | Result |
|---|---|---|---|---|---|---|---|---|---|---|
| [01](01-baseline.md) | 2026-11-15 | None: baseline, conditional-update holds | (reference) | 2 fans/s, 120 s | 95 / 113 / 39 / 45 ms | 3-10 ms, checkout 16-28 ms | 15% | 0 of 1,188 | All 0 | Reference point. Server far from its limits; "available seats" slowness is outside the app |

## Open questions

- How large is an "available seats" response, and does its size explain the ~100 ms spent outside the app?
- Where is the knee: at what arrival rate do response times or errors start rising, and which resource gives out first?
- Under an on-sale rush (`-DpickFromFirst=10`), what share of holds end in `409`, and how do the three hold strategies compare?

## Candidate changes

To try one at a time, depending on what the stress test shows:

| Change | Expected to help with |
|---|---|
| Compression in Caddy (`encode gzip`) | Large responses, like "available seats" |
| Smaller or leaner seat pages | Large responses |
| Database connection pool size | Requests waiting for a connection |
| Virtual threads | Threads blocked waiting on the database or the provider |
| Optimistic or pessimistic holds instead of conditional update | Behaviour under contention |
| Rate limiting | Keeping the system responsive when demand exceeds capacity |
