package io.github.themusicianone.ticketing_backend.order;

import io.github.themusicianone.ticketing_backend.payment.PaymentGateway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;

@Service
public class CheckoutService {

    // inject OrderService, OrderRepository, PaymentGateway
    private final PaymentGateway paymentGateway;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public CheckoutService(PaymentGateway paymentGateway, OrderRepository orderRepository, OrderService orderService) {
        this.paymentGateway = paymentGateway;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }
    public OrderResponse checkout(Long holdId, Long userId, String idempotencyKey) {
        // 1. A repeated request returns the original order
        Optional<Order> existing = orderRepository.findByUser_IdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), holdId);
        }

        // 2. Transaction 1
        Order order;
        try {
            order = orderService.startCheckout(holdId, userId, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            // Another request with the same key won the race a moment ago
            return replay(orderRepository.findByUser_IdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(), holdId);
        }

        // 3. The network call, outside any transaction
        PaymentGateway.CreatedPayment payment;
        try {
            payment = paymentGateway.createPayment(new PaymentGateway.PaymentRequest(
                    "order-" + order.getId(), order.getTotalCents(), "Order " + order.getId()));
        } catch (RuntimeException e) {
            orderService.failOrder(order.getId(), OrderStatus.FAILED);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Payment provider unavailable, please try again");
        }

        // 4. Transaction 2
        Order withPayment = orderService.attachPayment(order.getId(), payment.paymentId(), payment.checkoutUrl());
        return OrderResponse.from(withPayment);
    }

    private OrderResponse replay(Order order, Long holdId) {
        if (!order.getHold().getId().equals(holdId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency key was already used for a different request");
        }
        return OrderResponse.from(order);
    }
}
