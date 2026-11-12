package io.github.themusicianone.ticketing_backend.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.themusicianone.ticketing_backend.order.OrderService;
import io.github.themusicianone.ticketing_backend.order.OrderStatus;
import io.github.themusicianone.ticketing_backend.order.OrderTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Sends webhooks to the app the way the payment provider would,
 * signed with the shared secret, and checks what happens to the order.
 */
class PaymentWebhookTest extends OrderTestSupport {

    @Value("${app.payments.webhook-secret}")
    private String webhookSecret;

    @Autowired
    private OrderService orderService;

    // ---- Applying events -------------------------------------------------------------

    @Test
    void paidWebhookConfirmsTheOrder() {
        PendingOrder order = createPendingOrder(2);

        sendSigned(event(order.paymentId(), "paid"))
                .expectStatus().isOk();

        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("SOLD");
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(2);
    }

    @Test
    void failedWebhookReleasesTheSeats() {
        PendingOrder order = createPendingOrder(2);

        sendSigned(event(order.paymentId(), "failed"))
                .expectStatus().isOk();

        assertThat(orderStatus(order.orderId())).isEqualTo("FAILED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
        assertThat(ticketsForOrder(order.orderId())).isZero();
    }

    @Test
    void canceledWebhookExpiresTheOrder() {
        PendingOrder order = createPendingOrder(1);

        sendSigned(event(order.paymentId(), "canceled"))
                .expectStatus().isOk();

        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
    }

    @Test
    void openStatusChangesNothing() {
        PendingOrder order = createPendingOrder(1);

        sendSigned(event(order.paymentId(), "open"))
                .expectStatus().isOk();

        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");
    }

    // ---- Duplicates and unknown payments ----------------------------------------------

    @Test
    void duplicateEventIsAppliedOnce() {
        PendingOrder order = createPendingOrder(2);
        String body = event(order.paymentId(), "paid");

        sendSigned(body).expectStatus().isOk();
        sendSigned(body).expectStatus().isOk();

        assertThat(ticketsForOrder(order.orderId())).isEqualTo(2);
        assertThat(recordedEvents(order.paymentId())).isEqualTo(1);
    }

    @Test
    void separateEventsForTheSamePaymentAreBothRecorded() {
        PendingOrder order = createPendingOrder(1);

        sendSigned(event(order.paymentId(), "paid")).expectStatus().isOk();
        sendSigned(event(order.paymentId(), "paid")).expectStatus().isOk();

        // Different event IDs, so both are recorded, but the order is still confirmed only once
        assertThat(recordedEvents(order.paymentId())).isEqualTo(2);
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(1);
    }

    @Test
    void unknownPaymentIsAcknowledgedSoTheProviderStopsRetrying() {
        String paymentId = "tr_unknown_" + UUID.randomUUID();

        sendSigned(event(paymentId, "paid"))
                .expectStatus().isOk();

        assertThat(recordedEvents(paymentId)).isEqualTo(1);
    }

    @Test
    void paymentAfterTheOrderEndedIsNotConfirmed() {
        PendingOrder order = createPendingOrder(2);
        orderService.failOrder(order.orderId(), OrderStatus.EXPIRED);

        sendSigned(event(order.paymentId(), "paid"))
                .expectStatus().isOk();

        // The customer paid too late: the seats stay released and no tickets are issued
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
        assertThat(ticketsForOrder(order.orderId())).isZero();
    }

    // ---- Rejected webhooks --------------------------------------------------------------

    @Test
    void invalidSignatureIsRejected() {
        PendingOrder order = createPendingOrder(1);
        String body = event(order.paymentId(), "paid");

        send(body, "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64))
                .expectStatus().isUnauthorized();

        assertNothingChanged(order);
    }

    @Test
    void tamperedBodyIsRejected() {
        PendingOrder order = createPendingOrder(1);
        String signedBody = event(order.paymentId(), "failed");
        String header = signature(signedBody, Instant.now(), webhookSecret);

        // Signed as "failed", but sent as "paid"
        send(signedBody.replace("\"failed\"", "\"paid\""), header)
                .expectStatus().isUnauthorized();

        assertNothingChanged(order);
    }

    @Test
    void signatureWithTheWrongSecretIsRejected() {
        PendingOrder order = createPendingOrder(1);
        String body = event(order.paymentId(), "paid");

        send(body, signature(body, Instant.now(), "not-the-real-secret"))
                .expectStatus().isUnauthorized();

        assertNothingChanged(order);
    }

    @Test
    void oldWebhookIsRejected() {
        PendingOrder order = createPendingOrder(1);
        String body = event(order.paymentId(), "paid");

        // Correctly signed, but 10 minutes old: a captured webhook being replayed
        send(body, signature(body, Instant.now().minus(Duration.ofMinutes(10)), webhookSecret))
                .expectStatus().isUnauthorized();

        assertNothingChanged(order);
    }

    @Test
    void webhookFromTheFutureIsRejected() {
        PendingOrder order = createPendingOrder(1);
        String body = event(order.paymentId(), "paid");

        send(body, signature(body, Instant.now().plus(Duration.ofMinutes(10)), webhookSecret))
                .expectStatus().isUnauthorized();

        assertNothingChanged(order);
    }

    @Test
    void missingOrMalformedSignatureIsRejected() {
        PendingOrder order = createPendingOrder(1);
        String body = event(order.paymentId(), "paid");

        send(body, null).expectStatus().isUnauthorized();
        send(body, "garbage").expectStatus().isUnauthorized();
        send(body, "t=not-a-number,v1=abc").expectStatus().isUnauthorized();
        send(body, "t=" + Instant.now().getEpochSecond() + ",v1=not-hex").expectStatus().isUnauthorized();

        assertNothingChanged(order);
    }

    @Test
    void validSignatureOnInvalidJsonIsABadRequest() {
        sendSigned("this is not json")
                .expectStatus().isBadRequest();
    }

    // ---- Helpers ------------------------------------------------------------------------

    private record PendingOrder(Long orderId, String paymentId, List<Long> seatIds) {
    }

    private PendingOrder createPendingOrder(int seatCount) {
        Long eventId = createEventWithSeats(seatCount + 2);
        List<Long> seats = seatIds(eventId).subList(0, seatCount);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);
        Long orderId = checkoutOk(holdId, token, newKey()).id();

        String paymentId = jdbc.queryForObject(
                "SELECT payment_id FROM orders WHERE id = ?", String.class, orderId);
        return new PendingOrder(orderId, paymentId, seats);
    }

    private void assertNothingChanged(PendingOrder order) {
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");
        assertThat(seatStatuses(order.seatIds())).containsOnly("HELD");
        assertThat(recordedEvents(order.paymentId())).isZero();
    }

    private long recordedEvents(String paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_webhook_events WHERE payment_id = ?", Long.class, paymentId);
    }

    /** A webhook body like the provider sends, with a fresh event ID. */
    private String event(String paymentId, String status) {
        return """
                {"eventId":"evt_%s","paymentId":"%s","status":"%s","createdAt":"%s"}"""
                .formatted(UUID.randomUUID(), paymentId, status, Instant.now());
    }

    private RestTestClient.ResponseSpec sendSigned(String body) {
        return send(body, signature(body, Instant.now(), webhookSecret));
    }

    private RestTestClient.ResponseSpec send(String body, String signatureHeader) {
        return client.post()
                .uri("/webhooks/payments")
                .headers(h -> {
                    if (signatureHeader != null) {
                        h.set("Payment-Signature", signatureHeader);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .exchange();
    }

    /** Signs a body exactly the way the provider does. */
    private static String signature(String body, Instant timestamp, String secret) {
        try {
            long seconds = timestamp.getEpochSecond();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((seconds + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body.getBytes(StandardCharsets.UTF_8));
            return "t=" + seconds + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}