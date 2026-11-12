# 0002: Seat holds and preventing double-booking

**Status:** Accepted
**Date:** 2026-11-11
**Milestone:** 4 (Seat holds)

## Context

When a customer picks seats, they get **10 minutes** to pay, and nobody else can take those seats in the meantime. If they don't pay or cancel, the seats go back on sale.

The core requirement is that **a seat can never be held by two customers at once**, even when many customers try to hold it at the same moment, which is exactly what happens when popular tickets go on sale.

The obvious implementation is unsafe:

```text
1. Read the seats and check they are AVAILABLE
2. Mark them HELD and save
```

Two requests can both pass step 1 before either reaches step 2. Both then mark the same seat as held, and both customers are told they got it. This is known as a **check-then-act** race, or **time-of-check to time-of-use (TOCTOU)**, and it causes a **lost update**: the second write silently replaces the first. Every fix below works by making the check and the change happen as one step that nothing can interrupt.

---

## 1. Data model

**Options**

| Option | Notes |
|---|---|
| A `held_by` column on each seat | Simple, but can't group seats bought together, or track a hold's expiry and status |
| **A `holds` table, with `seats.hold_id` pointing to it (chosen)** | One hold groups several seats, with its own owner, status and expiry |
| A `hold_seats` join table | Keeps a permanent record of every hold's seats, at the cost of another table and more writes |

**Why**

- Customers usually buy several seats together, and those seats should succeed or fail together.
- A hold has its own lifecycle: `ACTIVE`, then `RELEASED`, `EXPIRED`, or later `CONVERTED` into an order.
- A database `CHECK` keeps seats and holds consistent: an `AVAILABLE` seat can never point to a hold, and a `HELD` seat must.

**Trade-offs we accept**

- When a hold is released or expires, its seats' `hold_id` is cleared, so the hold no longer records which seats it had. If that history is needed later, a `hold_seats` table can add it.

---

## 2. Holds are all-or-nothing

A request for several seats either holds all of them or none. If any seat is unavailable, belongs to another event, or doesn't exist, the whole request fails with `409 Conflict` and the transaction rolls back, including the newly created hold.

**Why:** a customer who asked for four seats together shouldn't end up with three.

---

## 3. Preventing double-booking: three strategies

All three are implemented behind one interface, `SeatHoldStrategy`, and chosen with the `app.holds.strategy` property. The rest of the hold logic is identical, so they can be compared fairly.

### Conditional update (default)

```sql
UPDATE seats
SET status = 'HELD', hold_id = ?, version = version + 1
WHERE id IN (...) AND event_id = ? AND status = 'AVAILABLE'
```

The availability check is part of the `UPDATE` itself. If two transactions target the same seat, PostgreSQL makes the second wait for the first's row lock, then **re-checks** the `WHERE` condition against the committed data. By then the seat is `HELD`, so the second update changes fewer rows than requested, and the service rolls back with `409`.

### Optimistic locking

Load the seats, check them in Java, change them, and save. `@Version` on `Seat` makes Hibernate add the version it read to the update:

```sql
UPDATE seats SET ..., version = 4 WHERE id = ? AND version = 3
```

If another transaction changed the seat in between, the version no longer matches, the update changes 0 rows, and Hibernate throws `OptimisticLockingFailureException`, which becomes `409`.

### Pessimistic locking

Lock the seats **before** checking them, with `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`). Competing requests wait for the lock, then see `HELD` and fail with `409`.

### Comparison

| | Conditional update | Optimistic | Pessimistic |
|---|---|---|---|
| Database round trips | One `UPDATE` | `SELECT`, then `UPDATE`s | `SELECT ... FOR UPDATE`, then `UPDATE`s |
| What a losing request does | Waits briefly for the row lock, then changes 0 rows | Does its work, then fails when saving | Waits for the lock, then sees `HELD` |
| Wasted work under contention | Very little | High: losers do the full read and check first | Low, but waiting holds a database connection |
| Business rules | Must fit in a `WHERE` clause | Any Java logic | Any Java logic |
| Risks | Hard to express complex rules | Many failed requests under heavy contention | Lock waits, and deadlocks if locks are taken in different orders |

### Decision: conditional update as the default

- It's the fewest database round trips, and keeps lock time to the length of one statement.
- The rule, "only `AVAILABLE` seats can be held", is simple enough to express in SQL.
- The other two stay available for comparison under load in milestone 8. Pessimistic locking would be the better choice if holding seats needed checks that can't be written as a `WHERE` clause.

---

## 4. Avoiding deadlocks

Both locking strategies lock several rows. If one request locks seat 1 then seat 2, while another locks seat 2 then seat 1, each waits for the other forever. PostgreSQL detects this and aborts one transaction, which would reach the client as a `500`.

**What we do**

- Seats are always loaded and locked in **ascending ID order** (`ORDER BY s.id`).
- `hibernate.order_updates=true` makes Hibernate also send its updates in ID order.
- Releasing a hold and the expiry job both change **the hold first, then its seats**, so they lock rows in the same order and can't deadlock with each other.

---

## 5. Releasing and expiring holds

**Releasing** uses a conditional update on the hold: it only changes a hold that belongs to the caller and is still `ACTIVE`. If it changes nothing, the service checks why, returning `404` if the hold isn't the caller's and `409` if it's no longer active. This stays safe if a customer cancels at the same moment the hold expires.

**Expiring** runs as a scheduled job every 30 seconds:

1. Find up to 500 active holds whose `expires_at` has passed, using a partial index on active holds.
2. Mark them `EXPIRED`, again only if still `ACTIVE`.
3. Release their seats.

**Why batches of 500:** each run's updates lock the rows they change until the transaction commits. Small batches keep those locks brief, so the job barely gets in the way of customers. Anything left over is handled in the next run.

**Testability:** `expireDueHolds` receives the current time as a parameter instead of reading the clock. The job passes the real time, and tests pass a time 11 minutes ahead, so expiry can be tested instantly. The job is switched off in tests, so it can't change data in the middle of one.

---

## 6. Ownership

A hold's owner comes from the token's `sub` claim, never from the request body. Holds are looked up with `findByIdAndUser_Id`, so another customer's hold simply isn't found.

Requests for someone else's hold return **`404`, not `403`**, with the same message as a hold that doesn't exist. That way, nobody can find out which hold IDs are valid. Admins get the same treatment, since they have no reason to see customers' holds.

---

## 7. Errors stay out of the domain

`Seat.holdFor(hold)` throws a `SeatNotAvailableException` rather than an HTTP exception, so the entity only knows the business rule. `GlobalExceptionHandler` translates it, and `OptimisticLockingFailureException`, into `409`.

The services still throw `ResponseStatusException` directly, which is a pragmatic compromise in a codebase this size. The same pattern could replace those later.

---

## Evidence

`HoldRaceTest` runs once per strategy, through `ConditionalUpdateRaceTest`, `OptimisticLockingRaceTest` and `PessimisticLockingRaceTest`. Each run first checks that Spring injected the expected strategy, then runs two races, five times each, because race bugs don't appear on every run:

| Race | Setup | Must hold |
|---|---|---|
| One seat | 200 customers hold the same seat at the same moment | Exactly one `201`, 199 `409`s, no `5xx`, one held seat and one active hold in the database |
| Overlapping pairs | 200 customers each request one of six overlapping pairs from four seats | No `5xx`, every success holds both its seats, no seat in two holds |

The requests are started on virtual threads and released together by a `CountDownLatch`. The results are checked directly in the database with SQL, independent of the JPA code.

**Result:** all three strategies pass every run.

**Reproducing the failure:** removing `AND status = 'AVAILABLE'` from the conditional update, and checking availability in Java first, turns it into the unsafe check-then-act version. The one-seat race then reports more than one successful hold. Recording the exact numbers from such a run would make this section stronger.

---

## Known limitations and future work

| Limitation | Possible fix |
|---|---|
| Two requests from the **same** customer at the same moment can both pass the 6-seat check, together exceeding it. Seats still can't be double-booked | Lock the customer's row, or enforce the limit with a database constraint or counter |
| Expired holds keep their seats for up to 30 seconds, until the next job run | Also treat seats on expired-but-unprocessed holds as available when creating holds |
| Several app instances would all run the expiry job. Safe because of conditional updates, but wasteful | ShedLock, or a database-level scheduler |
| Pessimistic lock waits have no timeout, so requests could pile up under heavy load | Set a lock timeout and return `409` or `503` when it's reached |
| A released or expired hold forgets its seats | A `hold_seats` history table |
| Strategy speed hasn't been measured under realistic load yet | Load tests in milestones 7 and 8 |