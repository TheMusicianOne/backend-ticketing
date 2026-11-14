package io.github.themusicianone.ticketing_backend.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;

import io.github.themusicianone.ticketing_backend.order.OrderService;
import io.github.themusicianone.ticketing_backend.order.OrderStatus;
import io.github.themusicianone.ticketing_backend.order.OrderTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Late payments and refunds, against the stub provider.
 *
 * A "late" order is one that already ended (EXPIRED) when the provider reports it as paid.
 */
class RefundTest extends OrderTestSupport {

    @Autowired
    private PaymentOutcomeService outcomeService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private StubPaymentGateway stubGateway;

    @BeforeEach
    void finishLeftoverRefunds() {
        // Earlier tests may leave refunds waiting in the shared database.
        // Clear the queue, so each test's counts only include its own orders.
        jdbc.update("""
                UPDATE orders SET status = 'REFUNDED', refunded_at = now()
                WHERE refund_required_at IS NOT NULL AND refunded_at IS NULL
                """);
    }

    // ---- Detecting late payments -----------------------------------------------------

    @Test
    void latePaymentIsMarkedForARefund() {
        PendingOrder order = lateOrder(2);

        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(refundRequiredAt(order.orderId())).isNotNull();
        assertThat(refundedAt(order.orderId())).isNull();

        // The seats stay released, and nothing is sold
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
        assertThat(ticketsForOrder(order.orderId())).isZero();
    }

    @Test
    void reportingTheSameLatePaymentAgainKeepsTheFirstDecision() {
        PendingOrder order = lateOrder(1);
        Timestamp first = refundRequiredAt(order.orderId());

        // A duplicate webhook, or reconciliation, reports the same payment again
        outcomeService.apply(order.orderId(), ProviderStatus.PAID);

        assertThat(refundRequiredAt(order.orderId())).isEqualTo(first);
    }

    @Test
    void paymentOnTimeIsConfirmedNotRefunded() {
        PendingOrder order = pendingOrder(2);

        outcomeService.apply(order.orderId(), ProviderStatus.PAID);

        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(refundRequiredAt(order.orderId())).isNull();
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(2);
    }

    @Test
    void declinedPaymentNeedsNoRefund() {
        PendingOrder order = pendingOrder(1);

        outcomeService.apply(order.orderId(), ProviderStatus.FAILED);

        assertThat(orderStatus(order.orderId())).isEqualTo("FAILED");
        assertThat(refundRequiredAt(order.orderId())).isNull();
    }

    // ---- Processing refunds ------------------------------------------------------------

    @Test
    void waitingRefundIsProcessed() {
        PendingOrder order = lateOrder(2);

        int refunded = refundService.processRefunds();

        assertThat(refunded).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDED");
        assertThat(refundedAt(order.orderId())).isNotNull();
        assertThat(stubGateway.getStatus(order.paymentId())).isEqualTo(ProviderStatus.REFUNDED);
    }

    @Test
    void severalWaitingRefundsAreAllProcessed() {
        PendingOrder first = lateOrder(1);
        PendingOrder second = lateOrder(1);

        int refunded = refundService.processRefunds();

        assertThat(refunded).isEqualTo(2);
        assertThat(orderStatus(first.orderId())).isEqualTo("REFUNDED");
        assertThat(orderStatus(second.orderId())).isEqualTo("REFUNDED");
    }

    @Test
    void runningTwiceRefundsNothingTheSecondTime() {
        lateOrder(1);

        refundService.processRefunds();
        int secondRun = refundService.processRefunds();

        assertThat(secondRun).isZero();
    }

    @Test
    void confirmedOrderIsNeverRefunded() {
        PendingOrder order = pendingOrder(1);
        outcomeService.apply(order.orderId(), ProviderStatus.PAID);

        int refunded = refundService.processRefunds();

        assertThat(refunded).isZero();
        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(stubGateway.getStatus(order.paymentId())).isNotEqualTo(ProviderStatus.REFUNDED);
    }

    @Test
    void nothingWaitingMeansNothingRefunded() {
        assertThat(refundService.processRefunds()).isZero();
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

    /** An order that expired, after which the provider reports its payment as paid anyway. */
    private PendingOrder lateOrder(int seatCount) {
        PendingOrder order = pendingOrder(seatCount);
        orderService.failOrder(order.orderId(), OrderStatus.EXPIRED);
        stubGateway.setStatus(order.paymentId(), ProviderStatus.PAID);
        outcomeService.apply(order.orderId(), ProviderStatus.PAID);
        return order;
    }

    private Timestamp refundRequiredAt(Long orderId) {
        return jdbc.queryForObject(
                "SELECT refund_required_at FROM orders WHERE id = ?", Timestamp.class, orderId);
    }

    private Timestamp refundedAt(Long orderId) {
        return jdbc.queryForObject(
                "SELECT refunded_at FROM orders WHERE id = ?", Timestamp.class, orderId);
    }
}