package io.github.themusicianone.ticketing_backend.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.themusicianone.ticketing_backend.order.OrderService;
import io.github.themusicianone.ticketing_backend.order.OrderStatus;
import io.github.themusicianone.ticketing_backend.order.OrderTestSupport;
import io.github.themusicianone.ticketing_backend.payment.PaymentGateway.CreatedPayment;
import io.github.themusicianone.ticketing_backend.payment.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Refunds when the provider fails, using a Mockito mock in place of the provider.
 */
class RefundEdgeCaseTest extends OrderTestSupport {

    @MockitoBean
    private PaymentGateway gateway;

    @Autowired
    private RefundService refundService;

    @Autowired
    private PaymentOutcomeService outcomeService;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // Checkout still needs to create payments, so give each one a predictable ID
        when(gateway.createPayment(any())).thenAnswer(invocation -> {
            PaymentRequest request = invocation.getArgument(0);
            String id = "mock_" + request.idempotencyKey();
            return new CreatedPayment(id, "https://pay.example.test/checkout/" + id);
        });

        jdbc.update("""
                UPDATE orders SET status = 'REFUNDED', refunded_at = now()
                WHERE refund_required_at IS NOT NULL AND refunded_at IS NULL
                """);
    }

    @Test
    void providerFailureForOneOrderDoesNotStopTheOthers() {
        PendingOrder broken = lateOrder();
        PendingOrder fine = lateOrder();
        doThrow(new RuntimeException("Payment provider timed out"))
                .when(gateway).refund(eq(broken.paymentId()), anyString());

        int refunded = refundService.processRefunds();

        assertThat(refunded).isEqualTo(1);
        assertThat(orderStatus(fine.orderId())).isEqualTo("REFUNDED");

        // The failed one is still waiting, not lost
        assertThat(orderStatus(broken.orderId())).isEqualTo("EXPIRED");
        assertThat(refundedAt(broken.orderId())).isNull();
    }

    @Test
    void failedRefundIsRetriedOnTheNextRun() {
        PendingOrder order = lateOrder();
        doThrow(new RuntimeException("Payment provider timed out"))
                .doNothing()
                .when(gateway).refund(eq(order.paymentId()), anyString());

        refundService.processRefunds();
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");

        refundService.processRefunds();
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDED");
    }

    @Test
    void retriesUseTheSameRefundKey() {
        PendingOrder order = lateOrder();
        doThrow(new RuntimeException("Payment provider timed out"))
                .doNothing()
                .when(gateway).refund(eq(order.paymentId()), anyString());

        refundService.processRefunds();
        refundService.processRefunds();

        // Both attempts carried the same key, so the provider can never refund twice
        verify(gateway, times(2)).refund(order.paymentId(), "refund-order-" + order.orderId());
    }

    @Test
    void refundIsAskedForOnlyOnce() {
        PendingOrder order = lateOrder();
        doNothing().when(gateway).refund(anyString(), anyString());

        refundService.processRefunds();
        refundService.processRefunds();

        verify(gateway, times(1)).refund(eq(order.paymentId()), anyString());
    }

    // ---- Helpers --------------------------------------------------------------------

    private record PendingOrder(Long orderId, String paymentId, List<Long> seatIds) {
    }

    private PendingOrder lateOrder() {
        Long eventId = createEventWithSeats(3);
        List<Long> seats = seatIds(eventId).subList(0, 1);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);
        Long orderId = checkoutOk(holdId, token, newKey()).id();
        String paymentId = jdbc.queryForObject(
                "SELECT payment_id FROM orders WHERE id = ?", String.class, orderId);

        orderService.failOrder(orderId, OrderStatus.EXPIRED);
        outcomeService.apply(orderId, ProviderStatus.PAID);
        return new PendingOrder(orderId, paymentId, seats);
    }

    private Object refundedAt(Long orderId) {
        return jdbc.queryForObject(
                "SELECT refunded_at FROM orders WHERE id = ?", java.sql.Timestamp.class, orderId);
    }
}