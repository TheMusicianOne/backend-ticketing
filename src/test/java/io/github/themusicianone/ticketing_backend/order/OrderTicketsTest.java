package io.github.themusicianone.ticketing_backend.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;

class OrderTicketsTest extends OrderTestSupport {

    @Autowired
    private OrderService orderService;

    // ---- Confirmed orders ----------------------------------------------------------------

    @Test
    void confirmedOrderHasOneTicketPerSeat() {
        String token = customerToken();
        PlacedOrder order = confirmedOrder(token, 3);

        List<TicketResponse> tickets = ticketsOk(order.orderId(), token);

        assertThat(tickets).hasSize(3);
        assertThat(tickets).allSatisfy(ticket -> {
            assertThat(ticket.id()).isNotNull();
            assertThat(ticket.section()).isEqualTo("Floor");
            assertThat(ticket.rowLabel()).isEqualTo("1");
            assertThat(ticket.code()).isNotBlank();
            assertThat(ticket.issuedAt()).isNotNull();
        });
    }

    @Test
    void ticketsComeInSeatOrderAndMatchTheOrdersSeats() {
        String token = customerToken();
        PlacedOrder order = confirmedOrder(token, 3);

        List<Integer> seatNumbers = ticketsOk(order.orderId(), token).stream()
                .map(TicketResponse::seatNumber)
                .toList();

        // The order held the first three seats of the row
        assertThat(seatNumbers).containsExactly(1, 2, 3);
    }

    @Test
    void ticketCodesAreUniqueAndHardToGuess() {
        String token = customerToken();
        PlacedOrder order = confirmedOrder(token, 4);

        List<String> codes = ticketsOk(order.orderId(), token).stream()
                .map(TicketResponse::code)
                .toList();

        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).allMatch(code -> code.length() >= 20);
        assertThat(codes).noneMatch(code -> code.contains(String.valueOf(order.orderId())));
    }

    // ---- Orders without tickets ------------------------------------------------------

    @Test
    void pendingOrderHasNoTicketsYet() {
        String token = customerToken();
        PlacedOrder order = placeOrder(token, 2);

        assertThat(ticketsOk(order.orderId(), token)).isEmpty();
    }

    @Test
    void failedOrderHasNoTickets() {
        String token = customerToken();
        PlacedOrder order = placeOrder(token, 2);
        orderService.failOrder(order.orderId(), OrderStatus.FAILED);

        assertThat(ticketsOk(order.orderId(), token)).isEmpty();
    }

    // ---- Who can see them ------------------------------------------------------------

    @Test
    void otherCustomerCannotSeeTheTickets() {
        PlacedOrder order = confirmedOrder(customerToken(), 2);

        getTickets(order.orderId(), customerToken())
                .expectStatus().isNotFound();
    }

    @Test
    void otherCustomersTicketsAndUnknownOrderLookTheSame() {
        PlacedOrder order = confirmedOrder(customerToken(), 1);
        String otherToken = customerToken();

        String otherCustomers = getTickets(order.orderId(), otherToken)
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String unknown = getTickets(999999L, otherToken)
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(detailOf(otherCustomers)).isEqualTo(detailOf(unknown));
    }

    @Test
    void unknownOrderReturnsNotFound() {
        getTickets(999999L, customerToken())
                .expectStatus().isNotFound();
    }

    @Test
    void viewingTicketsRequiresLogin() {
        PlacedOrder order = confirmedOrder(customerToken(), 1);

        getTickets(order.orderId(), null)
                .expectStatus().isUnauthorized();
    }

    // ---- Helpers --------------------------------------------------------------------

    private record PlacedOrder(Long orderId, List<Long> seatIds) {
    }

    private PlacedOrder placeOrder(String token, int seatCount) {
        Long eventId = createEventWithSeats(seatCount + 2);
        List<Long> seats = seatIds(eventId).subList(0, seatCount);
        Long holdId = createHold(eventId, token, seats);
        Long orderId = checkoutOk(holdId, token, newKey()).id();
        return new PlacedOrder(orderId, seats);
    }

    /** An order whose payment went through, as if the webhook had arrived. */
    private PlacedOrder confirmedOrder(String token, int seatCount) {
        PlacedOrder order = placeOrder(token, seatCount);
        orderService.confirmOrder(order.orderId());
        return order;
    }

    private RestTestClient.ResponseSpec getTickets(Long orderId, String token) {
        return client.get()
                .uri("/orders/{orderId}/tickets", orderId)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .exchange();
    }

    private List<TicketResponse> ticketsOk(Long orderId, String token) {
        return getTickets(orderId, token)
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<TicketResponse>>() {})
                .returnResult()
                .getResponseBody();
    }

    private String detailOf(String problemJson) {
        int start = problemJson.indexOf("\"detail\":\"") + "\"detail\":\"".length();
        return problemJson.substring(start, problemJson.indexOf('"', start));
    }
}