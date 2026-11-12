package io.github.themusicianone.ticketing_backend.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * confirmOrder and failOrder are called by webhooks and the reconciliation job,
 * so these tests call them directly on orders created through a real checkout.
 */
class OrderServiceTest extends OrderTestSupport {

    @Autowired
    private OrderService orderService;

    // ---- Confirming ------------------------------------------------------------------

    @Test
    void confirmingSellsTheSeatsAndIssuesTickets() {
        PendingOrder pending = createPendingOrder(3);

        ConfirmResult result = orderService.confirmOrder(pending.orderId());

        assertThat(result).isEqualTo(ConfirmResult.CONFIRMED);
        assertThat(orderStatus(pending.orderId())).isEqualTo("CONFIRMED");
        assertThat(holdStatus(pending.holdId())).isEqualTo("CONVERTED");
        assertThat(seatStatuses(pending.seatIds())).containsOnly("SOLD");
        assertThat(ticketsForOrder(pending.orderId())).isEqualTo(3);
    }

    @Test
    void ticketsHaveOneSeatEachAndUniqueCodes() {
        PendingOrder pending = createPendingOrder(3);

        orderService.confirmOrder(pending.orderId());

        List<Long> ticketSeats = jdbc.queryForList(
                "SELECT seat_id FROM tickets WHERE order_id = ? ORDER BY seat_id",
                Long.class, pending.orderId());
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM tickets WHERE order_id = ?", String.class, pending.orderId());

        assertThat(ticketSeats).containsExactlyElementsOf(pending.seatIds());
        assertThat(codes).doesNotHaveDuplicates().allMatch(code -> code.length() >= 20);
    }

    @Test
    void confirmingTwiceIssuesTicketsOnce() {
        PendingOrder pending = createPendingOrder(2);

        orderService.confirmOrder(pending.orderId());
        ConfirmResult second = orderService.confirmOrder(pending.orderId());

        assertThat(second).isEqualTo(ConfirmResult.ALREADY_CONFIRMED);
        assertThat(ticketsForOrder(pending.orderId())).isEqualTo(2);
    }

    @Test
    void confirmingAFailedOrderAsksForARefund() {
        PendingOrder pending = createPendingOrder(2);

        orderService.failOrder(pending.orderId(), OrderStatus.EXPIRED);
        ConfirmResult result = orderService.confirmOrder(pending.orderId());

        // Paid too late: nothing is sold, and the caller must refund
        assertThat(result).isEqualTo(ConfirmResult.NEEDS_REFUND);
        assertThat(orderStatus(pending.orderId())).isEqualTo("EXPIRED");
        assertThat(seatStatuses(pending.seatIds())).containsOnly("AVAILABLE");
        assertThat(ticketsForOrder(pending.orderId())).isZero();
    }

    // ---- Failing ---------------------------------------------------------------------

    @Test
    void failingReleasesTheSeats() {
        PendingOrder pending = createPendingOrder(2);

        orderService.failOrder(pending.orderId(), OrderStatus.FAILED);

        assertThat(orderStatus(pending.orderId())).isEqualTo("FAILED");
        assertThat(holdStatus(pending.holdId())).isEqualTo("RELEASED");
        assertThat(seatStatuses(pending.seatIds())).containsOnly("AVAILABLE");
        assertThat(ticketsForOrder(pending.orderId())).isZero();
    }

    @Test
    void expiringReleasesTheSeats() {
        PendingOrder pending = createPendingOrder(2);

        orderService.failOrder(pending.orderId(), OrderStatus.EXPIRED);

        assertThat(orderStatus(pending.orderId())).isEqualTo("EXPIRED");
        assertThat(seatStatuses(pending.seatIds())).containsOnly("AVAILABLE");
    }

    @Test
    void releasedSeatsCanBeHeldAgain() {
        PendingOrder pending = createPendingOrder(2);

        orderService.failOrder(pending.orderId(), OrderStatus.FAILED);

        postHold(pending.eventId(), customerToken(), pending.seatIds())
                .expectStatus().isCreated();
    }

    @Test
    void failingAConfirmedOrderChangesNothing() {
        PendingOrder pending = createPendingOrder(2);

        orderService.confirmOrder(pending.orderId());
        orderService.failOrder(pending.orderId(), OrderStatus.FAILED);

        assertThat(orderStatus(pending.orderId())).isEqualTo("CONFIRMED");
        assertThat(seatStatuses(pending.seatIds())).containsOnly("SOLD");
        assertThat(ticketsForOrder(pending.orderId())).isEqualTo(2);
    }

    @Test
    void failingTwiceIsHarmless() {
        PendingOrder pending = createPendingOrder(2);

        orderService.failOrder(pending.orderId(), OrderStatus.FAILED);
        orderService.failOrder(pending.orderId(), OrderStatus.EXPIRED);

        assertThat(orderStatus(pending.orderId())).isEqualTo("FAILED");
    }

    @Test
    void cannotFailAnOrderIntoConfirmed() {
        PendingOrder pending = createPendingOrder(1);

        assertThatThrownBy(() -> orderService.failOrder(pending.orderId(), OrderStatus.CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(orderStatus(pending.orderId())).isEqualTo("PENDING");
    }

    // ---- Helpers -------------------------------------------------------------------

    private PendingOrder createPendingOrder(int seatCount) {
        Long eventId = createEventWithSeats(seatCount + 2);
        List<Long> seats = seatIds(eventId).subList(0, seatCount);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);
        OrderResponse order = checkoutOk(holdId, token, newKey());
        return new PendingOrder(eventId, holdId, order.id(), seats);
    }

    private record PendingOrder(Long eventId, Long holdId, Long orderId, List<Long> seatIds) {
    }
}