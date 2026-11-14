package io.github.themusicianone.ticketing_backend.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;

class OrderControllerTest extends OrderTestSupport {

    // ---- One order ---------------------------------------------------------------------

    @Test
    void ownerCanViewTheirOrder() {
        String token = customerToken();
        OrderResponse created = placeOrder(token, 2);

        OrderResponse order = getOrder(created.id(), token)
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(order.id()).isEqualTo(created.id());
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalCents()).isEqualTo(2 * SEAT_PRICE_CENTS);
        assertThat(order.checkoutUrl()).isEqualTo(created.checkoutUrl());
    }

    @Test
    void otherCustomerCannotViewTheOrder() {
        OrderResponse created = placeOrder(customerToken(), 1);

        getOrder(created.id(), customerToken())
                .expectStatus().isNotFound();
    }

    @Test
    void otherCustomersOrderAndUnknownOrderLookTheSame() {
        OrderResponse created = placeOrder(customerToken(), 1);
        String otherToken = customerToken();

        String otherCustomersOrder = getOrder(created.id(), otherToken)
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String unknownOrder = getOrder(999999L, otherToken)
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // Only the "instance" path differs, so compare the messages
        assertThat(detailOf(otherCustomersOrder)).isEqualTo(detailOf(unknownOrder));
    }

    @Test
    void unknownOrderReturnsNotFound() {
        getOrder(999999L, customerToken())
                .expectStatus().isNotFound();
    }

    @Test
    void viewingAnOrderRequiresLogin() {
        OrderResponse created = placeOrder(customerToken(), 1);

        getOrder(created.id(), null)
                .expectStatus().isUnauthorized();
    }

    // ---- The list of orders ------------------------------------------------------------

    @Test
    void customerSeesOnlyTheirOwnOrders() {
        String token = customerToken();
        OrderResponse mine = placeOrder(token, 1);
        placeOrder(customerToken(), 1); // someone else's

        listOrders(token, "")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.content[0].id").isEqualTo(mine.id());
    }

    @Test
    void newestOrderComesFirst() {
        String token = customerToken();
        OrderResponse older = placeOrder(token, 1);
        OrderResponse newer = placeOrder(token, 1);

        listOrders(token, "")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].id").isEqualTo(newer.id())
                .jsonPath("$.content[1].id").isEqualTo(older.id());
    }

    @Test
    void customerWithoutOrdersGetsAnEmptyList() {
        listOrders(customerToken(), "")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(0)
                .jsonPath("$.totalElements").isEqualTo(0);
    }

    @Test
    void ordersArePaged() {
        String token = customerToken();
        placeOrder(token, 1);
        placeOrder(token, 1);
        placeOrder(token, 1);

        listOrders(token, "?size=1")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.size").isEqualTo(1)
                .jsonPath("$.totalElements").isEqualTo(3)
                .jsonPath("$.totalPages").isEqualTo(3);
    }

    @Test
    void listingOrdersRequiresLogin() {
        listOrders(null, "")
                .expectStatus().isUnauthorized();
    }

    // ---- Helpers --------------------------------------------------------------------

    /** Holds seats on a fresh event and checks out, returning the new order. */
    private OrderResponse placeOrder(String token, int seatCount) {
        Long eventId = createEventWithSeats(seatCount + 2);
        List<Long> seats = seatIds(eventId).subList(0, seatCount);
        Long holdId = createHold(eventId, token, seats);
        return checkoutOk(holdId, token, newKey());
    }

    private RestTestClient.ResponseSpec getOrder(Long orderId, String token) {
        return client.get()
                .uri("/orders/{orderId}", orderId)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .exchange();
    }

    private RestTestClient.ResponseSpec listOrders(String token, String query) {
        return client.get()
                .uri("/orders" + query)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .exchange();
    }

    private String detailOf(String problemJson) {
        int start = problemJson.indexOf("\"detail\":\"") + "\"detail\":\"".length();
        return problemJson.substring(start, problemJson.indexOf('"', start));
    }
}