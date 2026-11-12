package io.github.themusicianone.ticketing_backend.order;

import java.time.Instant;

public record OrderResponse(
        Long id,
        Long holdId,
        Long eventId,
        OrderStatus status,
        int totalCents,
        String checkoutUrl,
        Instant paymentDeadline,
        Instant createdAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getHold().getId(),
                order.getEvent().getId(),
                order.getStatus(),
                order.getTotalCents(),
                order.getCheckoutUrl(),
                order.getPaymentDeadline(),
                order.getCreatedAt());
    }
}