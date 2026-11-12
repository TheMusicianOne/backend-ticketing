package io.github.themusicianone.ticketing_backend.payment;

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

import io.github.themusicianone.ticketing_backend.order.OrderService;
import io.github.themusicianone.ticketing_backend.order.OrderTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reconciliation against the stub provider, whose answers each test sets with setStatus.
 *
 * Time is controlled through the "now" passed to reconcile:
 *   AFTER_GRACE    - old enough to be checked, but before the payment deadline
 *   AFTER_DEADLINE - past the 15-minute payment deadline
 */
class PaymentReconciliationTest extends OrderTestSupport {

    private static final Duration AFTER_GRACE = Duration.ofMinutes(2);
    private static final Duration AFTER_DEADLINE = Duration.ofMinutes(16);

    @Autowired
    private PaymentReconciliationService reconciliationService;

    @Autowired
    private StubPaymentGateway stubGateway;

    @Autowired
    private PaymentOutcomeService outcomeService;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void finishLeftoverOrders() {
        // Other tests leave PENDING orders behind in the shared database. The job checks
        // the oldest first, in batches, so those could crowd out this test's order.
        jdbc.update("UPDATE orders SET status = 'FAILED' WHERE status = 'PENDING'");
    }

    // ---- Following the provider's answer -------------------------------------------

    @Test
    void paidPaymentConfirmsTheOrder() {
        PendingOrder order = pendingOrder(2);
        stubGateway.setStatus(order.paymentId(), ProviderStatus.PAID);

        int resolved = reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(resolved).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("SOLD");
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(2);
    }

    @Test
    void failedPaymentFailsTheOrder() {
        PendingOrder order = pendingOrder(2);
        stubGateway.setStatus(order.paymentId(), ProviderStatus.FAILED);

        reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(orderStatus(order.orderId())).isEqualTo("FAILED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
    }

    @Test
    void canceledPaymentExpiresTheOrder() {
        PendingOrder order = pendingOrder(1);
        stubGateway.setStatus(order.paymentId(), ProviderStatus.CANCELED);

        reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
    }

    // ---- Open payments and the deadline ----------------------------------------------

    @Test
    void openPaymentBeforeTheDeadlineIsLeftAlone() {
        PendingOrder order = pendingOrder(1);

        int resolved = reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(resolved).isZero();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");
        assertThat(seatStatuses(order.seatIds())).containsOnly("HELD");
        assertThat(stubGateway.getStatus(order.paymentId())).isEqualTo(ProviderStatus.OPEN);
    }

    @Test
    void abandonedPaymentIsCanceledAtTheProviderAndTheOrderExpires() {
        PendingOrder order = pendingOrder(2);

        reconciliationService.reconcile(later(AFTER_DEADLINE));

        // Canceled at the provider first, so the customer can no longer pay for released seats
        assertThat(stubGateway.getStatus(order.paymentId())).isEqualTo(ProviderStatus.CANCELED);
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(seatStatuses(order.seatIds())).containsOnly("AVAILABLE");
    }

    @Test
    void paidPaymentAfterTheDeadlineIsStillConfirmed() {
        PendingOrder order = pendingOrder(1);
        stubGateway.setStatus(order.paymentId(), ProviderStatus.PAID);

        reconciliationService.reconcile(later(AFTER_DEADLINE));

        // The customer paid in time; only the webhook was lost. Don't punish them for that.
        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(stubGateway.getStatus(order.paymentId())).isEqualTo(ProviderStatus.PAID);
    }

    // ---- Which orders are checked ----------------------------------------------------

    @Test
    void brandNewOrderIsNotCheckedYet() {
        PendingOrder order = pendingOrder(1);
        stubGateway.setStatus(order.paymentId(), ProviderStatus.PAID);

        // Within the grace period, the webhook probably just hasn't arrived yet
        reconciliationService.reconcile(Instant.now());

        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");
    }

    @Test
    void orderAlreadyConfirmedByAWebhookIsNotTouched() {
        PendingOrder order = pendingOrder(2);
        orderService.confirmOrder(order.orderId());
        stubGateway.setStatus(order.paymentId(), ProviderStatus.PAID);

        int resolved = reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(resolved).isZero();
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(2);
    }

    @Test
    void runningTwiceChangesNothingTheSecondTime() {
        PendingOrder order = pendingOrder(1);
        stubGateway.setStatus(order.paymentId(), ProviderStatus.PAID);

        reconciliationService.reconcile(later(AFTER_GRACE));
        int secondRun = reconciliationService.reconcile(later(AFTER_GRACE));

        assertThat(secondRun).isZero();
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(1);
    }

    // ---- Webhooks and reconciliation at the same moment -----------------------------

    @Test
    void simultaneousOutcomesIssueTicketsOnce() throws Exception {
        PendingOrder order = pendingOrder(3);
        int callers = 20;

        // Webhooks and the reconciliation job all end up in apply(), possibly at once
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    outcomeService.apply(order.orderId(), ProviderStatus.PAID);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    errors.add(e);
                }
            }
        }

        assertThat(errors).as("errors while confirming at the same time").isEmpty();
        assertThat(orderStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(ticketsForOrder(order.orderId())).isEqualTo(3);
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