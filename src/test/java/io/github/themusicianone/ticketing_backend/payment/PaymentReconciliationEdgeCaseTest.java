package io.github.themusicianone.ticketing_backend.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.github.themusicianone.ticketing_backend.order.OrderTestSupport;
import io.github.themusicianone.ticketing_backend.payment.PaymentGateway.CreatedPayment;
import io.github.themusicianone.ticketing_backend.payment.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Cases the stub can't produce: errors, unknown payments, and a payment that
 * changes status between two calls. The provider is replaced by a Mockito mock.
 */
class PaymentReconciliationEdgeCaseTest extends OrderTestSupport {

    private static final Duration AFTER_GRACE = Duration.ofMinutes(2);
    private static final Duration AFTER_DEADLINE = Duration.ofMinutes(16);

    @MockitoBean
    private PaymentGateway gateway;

    @Autowired
    private PaymentReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        // Checkout still needs to create payments, so give each one a predictable ID
        when(gateway.createPayment(any())).thenAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0);
            String id = "mock_" + request.idempotencyKey();
            return new CreatedPayment(id, "https://pay.example.test/checkout/" + id);
        });

        jdbc.update("UPDATE orders SET status = 'FAILED' WHERE status = 'PENDING'");
    }

    @Test
    void paymentThatJustBecamePaidIsConfirmedNotExpired() {
        PendingOrder order = pendingOrder(2);

        // Open when first asked, but paid by the time the job tries to cancel it
        when(gateway.getStatus(order.paymentId()))
                .thenReturn(ProviderStatus.OPEN, ProviderStatus.PAID);
        when(gateway.cancel(order.paymentId())).thenReturn(false);

        reconciliationService.reconcile(later(AFTER_DEADLINE));

        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("SOLD");
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(2);
    }

    @Test
    void unknownPaymentBeforeTheDeadlineIsLeftAlone() {
        PendingOrder order = pendingOrder(1);
        when(gateway.getStatus(order.paymentId()))
                .thenThrow(new PaymentNotFoundException(order.paymentId()));

        reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");
        assertThat(seatStatuses(order.seatIds())).containsOnly("HELD");
    }

    @Test
    void unknownPaymentAfterTheDeadlineExpiresTheOrder() {
        PendingOrder order = pendingOrder(2);
        when(gateway.getStatus(order.paymentId()))
                .thenThrow(new PaymentNotFoundException(order.paymentId()));

        reconciliationService.reconcile(later(AFTER_DEADLINE));

        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
        verify(gateway, never()).cancel(order.paymentId());
    }

    @Test
    void providerErrorForOneOrderDoesNotStopTheOthers() {
        PendingOrder broken = pendingOrder(1);
        PendingOrder fine = pendingOrder(1);
        when(gateway.getStatus(broken.paymentId()))
                .thenThrow(new RuntimeException("Payment provider timed out"));
        when(gateway.getStatus(fine.paymentId()))
                .thenReturn(ProviderStatus.PAID);

        int resolved = reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(resolved).isEqualTo(1);
        assertThat(orderStatus(broken.orderId())).isEqualTo("PENDING");
        assertThat(orderStatus(fine.orderId())).isEqualTo("CONFIRMED");
    }

    @Test
    void orderWithAProviderErrorIsRetriedOnTheNextRun() {
        PendingOrder order = pendingOrder(1);
        when(gateway.getStatus(order.paymentId()))
                .thenThrow(new RuntimeException("Payment provider timed out"))
                .thenReturn(ProviderStatus.PAID);

        reconciliationService.reconcile(later(AFTER_GRACE));
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");

        reconciliationService.reconcile(later(AFTER_GRACE));
        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
    }

    // ---- Helpers --------------------------------------------------------------------

    private record PendingOrder(Long orderId, String paymentId, List<Long> seatIds) {
    }

    private PendingOrder pendingOrder(int seatCount) {
        Long eventId = createEventWithSeats(seatCount + 2);
        List<Long> seats = seatIds(eventId).subList(0, seatCount);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);
        Long orderId = checkoutOk(holdId, token, newKey()).id();
        String paymentId = jdbc.queryForObject(
                "SELECT payment_id FROM orders WHERE id = ?", String.class, orderId);
        return new PendingOrder(orderId, paymentId, seats);
    }

    private static Instant later(Duration duration) {
        return Instant.now().plus(duration);
    }
}