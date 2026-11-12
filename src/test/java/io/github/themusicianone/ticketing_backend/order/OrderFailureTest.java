package io.github.themusicianone.ticketing_backend.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.themusicianone.ticketing_backend.payment.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The payment provider is replaced by a mock that always fails,
 * to check that a failed checkout leaves nothing stuck.
 */
class CheckoutProviderFailureTest extends OrderTestSupport {

    @MockitoBean
    private PaymentGateway paymentGateway;

    @Test
    void providerFailureFailsTheOrderAndReleasesTheSeats() {
        when(paymentGateway.createPayment(any()))
                .thenThrow(new RuntimeException("Payment provider is down"));

        Long eventId = createEventWithSeats(5);
        List<Long> seats = seatIds(eventId).subList(0, 2);
        String token = customerToken();
        Long holdId = createHold(eventId, token, seats);

        checkout(holdId, token, newKey())
                .expectStatus().isEqualTo(503);

        Long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE hold_id = ?", Long.class, holdId);

        assertThat(orderStatus(orderId)).isEqualTo("FAILED");
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seatStatuses(seats)).containsOnly("AVAILABLE");
    }
}