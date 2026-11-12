package io.github.themusicianone.ticketing_backend.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.themusicianone.ticketing_backend.hold.HoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

class CheckoutControllerTest extends OrderTestSupport {

    @Autowired
    private HoldService holdService;

    // ---- The happy path ------------------------------------------------------------

    @Test
    void checkoutCreatesPendingOrderWithCheckoutUrl() {
        Long eventId = createEventWithSeats(5);
        List<Long> seats = seatIds(eventId).subList(0, 2);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);
        Instant before = Instant.now();

        EntityExchangeResult<OrderResponse> result = checkout(holdId, token, newKey())
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult();

        OrderResponse order = result.getResponseBody();
        assertThat(order.id()).isNotNull();
        assertThat(order.holdId()).isEqualTo(holdId);
        assertThat(order.eventId()).isEqualTo(eventId);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalCents()).isEqualTo(2 * SEAT_PRICE_CENTS);
        assertThat(order.checkoutUrl()).isNotBlank();
        assertThat(order.paymentDeadline())
                .isAfter(before.plus(Duration.ofMinutes(14)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(16)));
    }

    @Test
    void checkoutFreezesTheHoldAndKeepsSeatsHeld() {
        Long eventId = createEventWithSeats(5);
        List<Long> seats = seatIds(eventId).subList(0, 2);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);

        checkoutOk(holdId, token, newKey());

        assertThat(holdStatus(holdId)).isEqualTo("PAYMENT_PENDING");
        assertThat(seatStatuses(seats)).containsOnly("HELD");
    }

    // ---- Idempotency -----------------------------------------------------------------

    @Test
    void repeatedRequestReturnsTheSameOrder() {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));
        String key = newKey();

        OrderResponse first = checkoutOk(holdId, token, key);
        OrderResponse second = checkoutOk(holdId, token, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.checkoutUrl()).isEqualTo(first.checkoutUrl());
        assertThat(ordersForHold(holdId)).isEqualTo(1);
    }

    @Test
    void simultaneousDuplicateRequestsCreateOneOrder() throws Exception {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));
        String key = newKey();
        int requests = 20;

        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return checkout(holdId, token, key)
                            .expectBody(String.class)
                            .returnResult()
                            .getStatus()
                            .value();
                }));
            }
            ready.await();
            start.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        // Every request either got the order, or was told a checkout with this key is still running
        assertThat(statuses).allMatch(status -> status == 201 || status == 409);
        assertThat(statuses).contains(201);

        // Exactly one order exists, whatever each request was told
        assertThat(ordersForHold(holdId)).as("orders in the database").isEqualTo(1);
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE hold_id = ?", Long.class, holdId);

        // Once things settle, a retry with the same key returns that same order
        assertThat(checkoutOk(holdId, token, key).id()).isEqualTo(orderId);
    }

    @Test
    void sameKeyForDifferentHoldIsRejected() {
        Long eventId = createEventWithSeats(5);
        List<Long> seats = seatIds(eventId);
        String token = customerToken();
        Long firstHold = createHold(eventId, token, seats.subList(0, 1));
        Long secondHold = createHold(eventId, token, seats.subList(1, 2));
        String key = newKey();

        checkoutOk(firstHold, token, key);

        checkout(secondHold, token, key)
                .expectStatus().isEqualTo(422);

        assertThat(holdStatus(secondHold)).isEqualTo("ACTIVE");
    }

    @Test
    void differentKeyForSameHoldIsRejected() {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));

        checkoutOk(holdId, token, newKey());

        checkout(holdId, token, newKey())
                .expectStatus().isEqualTo(409);

        assertThat(ordersForHold(holdId)).isEqualTo(1);
    }

    @Test
    void differentCustomersCanUseTheSameKey() {
        Long eventId = createEventWithSeats(5);
        List<Long> seats = seatIds(eventId);
        String firstToken = customerToken();
        String secondToken = customerToken();
        Long firstHold = createHold(eventId, firstToken, seats.subList(0, 1));
        Long secondHold = createHold(eventId, secondToken, seats.subList(1, 2));
        String sharedKey = newKey();

        OrderResponse first = checkoutOk(firstHold, firstToken, sharedKey);
        OrderResponse second = checkoutOk(secondHold, secondToken, sharedKey);

        assertThat(second.id()).isNotEqualTo(first.id());
    }

    // ---- The Idempotency-Key header ------------------------------------------------

    @Test
    void missingIdempotencyKeyIsRejected() {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));

        checkout(holdId, token, null)
                .expectStatus().isBadRequest();

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void blankOrTooLongIdempotencyKeyIsRejected() {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));

        checkout(holdId, token, " ")
                .expectStatus().isBadRequest();

        checkout(holdId, token, "x".repeat(101))
                .expectStatus().isBadRequest();

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    // ---- Which holds can be checked out -------------------------------------------

    @Test
    void otherCustomersHoldReturnsNotFound() {
        Long eventId = createEventWithSeats(5);
        Long holdId = createHold(eventId, customerToken(), seatIds(eventId).subList(0, 1));

        checkout(holdId, customerToken(), newKey())
                .expectStatus().isNotFound();

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void unknownHoldReturnsNotFound() {
        checkout(999999L, customerToken(), newKey())
                .expectStatus().isNotFound();
    }

    @Test
    void releasedHoldCannotBeCheckedOut() {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));

        client.delete()
                .uri("/holds/{holdId}", holdId)
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isNoContent();

        checkout(holdId, token, newKey())
                .expectStatus().isEqualTo(409);
    }

    @Test
    void holdPastItsDeadlineCannotBeCheckedOut() {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));

        // Past its deadline, but the expiry job hasn't run yet, so it's still ACTIVE
        jdbc.update("UPDATE holds SET expires_at = now() - interval '1 minute' WHERE id = ?", holdId);

        checkout(holdId, token, newKey())
                .expectStatus().isEqualTo(409);

        assertThat(ordersForHold(holdId)).isZero();
    }

    @Test
    void checkoutRequiresLogin() {
        Long eventId = createEventWithSeats(5);
        Long holdId = createHold(eventId, customerToken(), seatIds(eventId).subList(0, 1));

        checkout(holdId, null, newKey())
                .expectStatus().isUnauthorized();
    }

    // ---- A frozen hold is protected -----------------------------------------------

    @Test
    void holdBeingPaidForCannotBeReleased() {
        Long eventId = createEventWithSeats(5);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seatIds(eventId).subList(0, 1));

        checkoutOk(holdId, token, newKey());

        client.delete()
                .uri("/holds/{holdId}", holdId)
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isEqualTo(409);

        assertThat(holdStatus(holdId)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void holdBeingPaidForIsNotExpired() {
        Long eventId = createEventWithSeats(5);
        List<Long> seats = seatIds(eventId).subList(0, 1);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);

        checkoutOk(holdId, token, newKey());
        holdService.expireDueHolds(Instant.now().plus(Duration.ofMinutes(11)));

        assertThat(holdStatus(holdId)).isEqualTo("PAYMENT_PENDING");
        assertThat(seatStatuses(seats)).containsOnly("HELD");
    }

    @Test
    void seatsBeingPaidForCountTowardsTheLimit() {
        Long eventId = createEventWithSeats(10);
        List<Long> seats = seatIds(eventId);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats.subList(0, 4));

        checkoutOk(holdId, token, newKey());

        // 4 seats waiting for payment + 3 more = 7, over the limit of 6
        postHold(eventId, token, seats.subList(4, 7))
                .expectStatus().isEqualTo(409);
    }
}