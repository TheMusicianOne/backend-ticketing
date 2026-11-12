package io.github.themusicianone.ticketing_backend.order;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.themusicianone.ticketing_backend.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Shared setup for checkout and order tests: events with seats, holds,
 * checkout requests, and direct database checks.
 */
abstract class OrderTestSupport extends IntegrationTest {

    protected static final int SEAT_PRICE_CENTS = 5000;

    @Autowired
    protected JdbcTemplate jdbc;

    protected String adminToken;

    @BeforeEach
    void loginAdmin() {
        adminToken = adminToken();
    }

    // ---- Test data -------------------------------------------------------------

    protected Long createEventWithSeats(int seatCount) {
        Long venueId = client.post()
                .uri("/venues")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"Order test venue\",\"city\":\"Amsterdam\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(IdOnly.class)
                .returnResult()
                .getResponseBody()
                .id();

        Long eventId = client.post()
                .uri("/events")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"name":"Order test","startsAt":"2099-06-12T20:00:00Z","venueId":%d}
                        """.formatted(venueId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(IdOnly.class)
                .returnResult()
                .getResponseBody()
                .id();

        client.post()
                .uri("/events/{eventId}/seats/generate", eventId)
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"sections":[{"name":"Floor","rows":1,"seatsPerRow":%d,"priceCents":%d}]}
                        """.formatted(seatCount, SEAT_PRICE_CENTS))
                .exchange()
                .expectStatus().isCreated();

        return eventId;
    }

    protected List<Long> seatIds(Long eventId) {
        return jdbc.queryForList(
                "SELECT id FROM seats WHERE event_id = ? ORDER BY id", Long.class, eventId);
    }

    protected Long createHold(Long eventId, String token, List<Long> seatIds) {
        return postHold(eventId, token, seatIds)
                .expectStatus().isCreated()
                .expectBody(IdOnly.class)
                .returnResult()
                .getResponseBody()
                .id();
    }

    protected RestTestClient.ResponseSpec postHold(Long eventId, String token, List<Long> seatIds) {
        String ids = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        return client.post()
                .uri("/events/{eventId}/holds", eventId)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"seatIds\":[" + ids + "]}")
                .exchange();
    }

    // ---- Checkout -----------------------------------------------------------------

    /** Sends a checkout request. Pass a null token or key to leave that header out. */
    protected RestTestClient.ResponseSpec checkout(Long holdId, String token, String idempotencyKey) {
        return client.post()
                .uri("/holds/{holdId}/checkout", holdId)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                    if (idempotencyKey != null) {
                        h.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .exchange();
    }

    protected OrderResponse checkoutOk(Long holdId, String token, String idempotencyKey) {
        return checkout(holdId, token, idempotencyKey)
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---- Database checks -------------------------------------------------------

    protected String holdStatus(Long holdId) {
        return jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId);
    }

    protected String orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    protected List<String> seatStatuses(List<Long> seatIds) {
        return seatIds.stream()
                .map(id -> jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, id))
                .toList();
    }

    protected long ordersForHold(Long holdId) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE hold_id = ?", Long.class, holdId);
    }

    protected long ticketsForOrder(Long orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM tickets WHERE order_id = ?", Long.class, orderId);
    }

    protected record IdOnly(Long id) {
    }
}