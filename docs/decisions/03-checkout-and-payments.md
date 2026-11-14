# 0003: Checkout and payments

**Status:** Accepted
**Date:** 2026-11-13
**Milestone:** 5 (Checkout and payments)

## Context

A customer who holds seats needs to pay for them, and receive tickets once the payment succeeds. Payments involve an outside party, the payment service provider (PSP), and a network between us. That brings problems a normal request doesn't have: the provider can be slow, fail, or answer in a way that leaves us unsure whether a payment happened at all.

The rule for this milestone: **repeated requests and failures must never charge a customer twice, sell a seat twice, or leave a paying customer without tickets.**

---

## 1. Redirect and webhook, instead of charging in the checkout request

**Options**

| Option | How it works |
|---|---|
| Synchronous charge | The checkout request charges the card and waits for the result |
| **Redirect and webhook (chosen)** | Checkout creates a payment at the provider and returns its checkout URL. The customer pays on the provider's page. The provider tells us the result later |

**Why**

- **Our backend never sees card details or bank logins.** The customer enters them on the provider's page, so we stay out of the strict security rules for handling card data (PCI DSS).
- **Payment is asynchronous.** With iDEAL or 3-D Secure, the customer approves the payment in their banking app, which can take seconds or minutes. Holding the checkout request open that long would tie up server threads and database connections, and the request would likely time out anyway.

So checkout returns an order in `PENDING` with a `checkoutUrl`, and the order is finished later. **Webhooks** are the main way we learn the outcome, within seconds. **Reconciliation** (decision 6) is the backup for webhooks that get lost.

---

## 2. No database transaction around network calls

Checkout does three things:

1. **Transaction 1:** freeze the hold and create the order.
2. **No transaction:** ask the provider to create a payment.
3. **Transaction 2:** save the payment ID and checkout URL on the order.

**Why**

- A transaction holds row locks until it ends. Holding them while waiting for the provider would block other requests for those rows.
- A transaction also holds a **database connection**. The pool is small, about 10 connections. With a slow provider, a few simultaneous checkouts could take every connection, and every other request needing the database, even browsing events, would wait. A slow provider would slow down the whole site.
- The provider might not answer at all. With a 3-second timeout and two retries, a transaction could stay open for about 10 seconds.

The same rule applies everywhere else: reconciliation, webhooks and refunds all call the provider outside a transaction, and only use short transactions to record results.

**A gap found by reasoning about crashes.** If the server crashes after step 1 but before step 3, the order is `PENDING` with no payment ID, its hold is frozen, and its seats stay held. Retries don't help, because they only exist in the memory of the running request. None of the jobs looked at such orders: hold expiry skips frozen holds, and reconciliation only checked orders with a payment ID. The seats would have stayed blocked forever.

**Fix:** reconciliation also expires pending orders **without** a payment ID once their payment deadline has passed, using the normal `failOrder`, so the order, hold and seats change together. The customer never received a checkout URL, so they can't have paid. Waiting for the deadline matters, because every order briefly has no payment ID between steps 1 and 3 of a normal checkout.

---

## 3. A hold is frozen while its payment is in progress

When checkout starts, the hold changes from `ACTIVE` to `PAYMENT_PENDING`. The hold expiry job only expires `ACTIVE` holds, so from then on the hold's 10-minute timer no longer matters. The order takes over, with its own **15-minute payment deadline**.

```text
Minute 0   hold seats                  hold ACTIVE, expires at minute 10
Minute 9   check out                   hold PAYMENT_PENDING, payment deadline at minute 24
Minute 10  expiry job runs             skips the frozen hold
Minute 12  customer approves payment   order CONFIRMED, seats SOLD
```

- Checkout is only allowed for a hold that hasn't expired yet (`expiresAt > now`), even if the expiry job hasn't run.
- If the customer never finishes paying, reconciliation cancels the payment and expires the order after the deadline, releasing the seats.

**Why:** if the hold could expire during payment, a customer whose bank took a few minutes could pay for seats that had already been released and sold to someone else.

**Trade-off we accept:** an abandoned payment can block seats for up to about 25 minutes, the hold time plus the payment window. Blocking seats a bit longer is much better than taking seats away from customers who are paying.

---

## 4. Idempotency keys at two levels

| Key | Created by | Protects against |
|---|---|---|
| `Idempotency-Key` header on checkout | The customer's app | A double-click, or the app retrying after a network error, creating a second order and payment |
| `order-<id>`, sent to the provider | Our server | Our own retries creating a second payment at the provider |

A repeated checkout with the same key returns the original order. Reusing a key for a different hold is a client bug, and returns `422`.

**Two identical requests at the same moment** are handled by three layers:

1. **A quick lookup** catches ordinary repeats.
2. **The row lock on the hold** serializes simultaneous requests. The second waits for the first, then finds the hold already frozen, looks the key up again, and returns the first request's order. If that order has no payment yet, it answers `409`, "still in progress, retry", the same approach Stripe uses.
3. **`UNIQUE (user_id, idempotency_key)`** is the final safety net: two orders with the same key can never exist.

---

## 5. Webhooks are verified, and applied once

The webhook endpoint is public, because the provider has no login. So anyone could send it a fake "paid" message. Three layers protect it (**defense in depth**):

| Layer | Protects against |
|---|---|
| **HMAC-SHA256 signature** over the timestamp and the **raw body**, using a secret shared with the provider | Forged webhooks: without the secret, nobody can produce a valid signature. Changing even one character of the body breaks it |
| **Timestamp tolerance of 5 minutes** | Replay attacks: a real webhook captured earlier and sent again later. The timestamp is covered by the signature, so it can't be changed |
| **Recorded event IDs**, with `ON CONFLICT DO NOTHING` | Duplicates: the provider may send the same event more than once |

On top of that, processing is idempotent: confirming an already confirmed order changes nothing.

The signature is checked against the **raw bytes** received, before parsing. Parsing and re-serializing the JSON could change spacing, and with it the signature.

Recording the event and applying it happen in **one transaction**. If applying fails, recording is undone too, so the provider's retry is processed instead of being ignored as a duplicate.

---

## 6. Reconciliation as the safety net

Webhooks can be lost. Every minute, a job asks the provider about orders that have been `PENDING` for longer than a minute, and acts on the answer:

| The provider says | Action |
|---|---|
| `paid` | Confirm the order |
| `failed` | Fail the order, releasing the seats |
| `canceled` or `expired` | Expire the order |
| `open`, before the payment deadline | Nothing: the customer may still be paying |
| `open`, after the deadline | **Cancel the payment at the provider**, then expire the order |
| Unknown payment, after the deadline | Expire the order |

**Why cancel before expiring:** otherwise the customer could still approve the open payment afterwards, and pay for seats they no longer hold. If the cancel fails because the payment became `paid` at that exact moment, the job checks again and confirms the order, so a customer who paid at the last second still gets their tickets.

Each order is handled separately: one failing order is logged and retried on the next run, without stopping the others. Webhooks and reconciliation both go through the same `apply` method, and its conditional updates mean whichever arrives first wins.

---

## 7. Retries when creating a payment

| Failure | Retried? | Why |
|---|---|---|
| Timeouts and connection problems | Yes | Often temporary |
| `5xx` from the provider | Yes | A problem on their side, often temporary |
| `4xx` from the provider | No | Our request is wrong, and sending it again gives the same answer |

At most 3 attempts, with exponential backoff and jitter (about 200 ms, then 400 ms), and a 3-second read timeout, so a customer waits at most about 10 seconds before checkout fails with `503`. Spring Framework 7's built-in `@Retryable` does this, without an extra library.

**Why retrying is safe:** every attempt sends the same key, `order-<id>`. If an earlier attempt already created the payment and only the answer was lost, the provider returns the existing payment (`200` instead of `201`) instead of creating a new one. The chaos test showed exactly that: `503`, `503`, `200`.

**A bug found by testing failures for real:** at first, timeouts were not retried. Depending on exactly when a timeout hits, `RestClient` reports it either as a `ResourceAccessException`, or as a general `RestClientException` wrapping a `SocketTimeoutException`. Only the first was in the retry list. Retrying on `IOException`, which the retry logic also matches against an exception's causes, covers both. Reading the code would not have revealed this.

Retries only exist while the request runs. After a crash, recovery is the jobs' responsibility (decisions 2 and 6).

---

## 8. Refunds: record the decision, then let a job act on it

A payment can succeed after its order already ended, for example when a customer's banking app took too long. The seats are gone, so the money must go back.

```text
1. Webhook or reconciliation reports "paid"
2. confirmOrder sees the order already ended → NEEDS_REFUND
3. In the same transaction: mark the order "refund required"
4. A refund job finds orders marked this way
5. It asks the provider to refund, with a fixed key: refund-order-<id>
6. It marks the order REFUNDED
```

**Why not call the provider at step 2:**

- Step 2 runs inside a transaction, and network calls don't belong there (decision 2).
- A refund must never be forgotten. If the call failed or the app crashed at that moment, a decision that only existed in memory would be lost, and the customer would have paid for nothing.

Writing the decision to the database first, in the same transaction that discovered the problem, means it survives crashes, outages and restarts. This is a simple form of the **outbox pattern**.

**A crash between steps 5 and 6** means the next run refunds again. That is safe: the refund key never changes, and the provider answers "already refunded" with success, so the money moves only once, and the order is then marked.

---

## 9. A fake payment provider, in FastAPI

**Why not a real PSP**

- Real PSPs have onboarding requirements and costs that don't fit a portfolio project.
- **Control over failures:** chaos settings make the provider slow, fail, lose responses, or drop and duplicate webhooks on demand. A real PSP's test mode can't. That control is how the timeout bug was found.
- **Load testing:** thousands of payments can be sent to our own provider, which isn't possible against a real PSP's test environment.
- **The backend doesn't care who it talks to:** everything goes through the `PaymentGateway` interface, so switching to Mollie or Stripe would mean writing one new class.
- Building it in Python with FastAPI also shows working across languages and services.

**What it simplifies compared with a real PSP**

- Payments live in memory and disappear when it restarts.
- No real money, no 3-D Secure screens, no separate authorize and capture steps.
- Webhook retries last seconds rather than hours or days.

---

## Known limitations and future work

| Limitation | Possible fix |
|---|---|
| The fake provider forgets payments on restart, so orders pointing at them are expired after their deadline | Persist payments. A real PSP never forgets them |
| If creating the payment fails after all retries, the order is failed and the seats released, so the customer must hold seats again | Put the hold back to `ACTIVE` instead |
| Scheduled jobs (hold expiry, reconciliation, refunds) run on every app instance. Safe because of conditional updates, but wasteful | ShedLock, or a single scheduler instance |
| The webhook timestamp tolerance relies on reasonably synchronized clocks | Monitor clock drift on servers |
| Abandoned payments can block seats for up to about 25 minutes | Shorter payment window, if load tests show it matters |
| Refunds are all-or-nothing | Partial refunds, if ever needed |