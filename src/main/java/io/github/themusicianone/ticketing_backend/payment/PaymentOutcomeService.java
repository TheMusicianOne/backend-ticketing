package io.github.themusicianone.ticketing_backend.payment;

import io.github.themusicianone.ticketing_backend.order.ConfirmResult;
import io.github.themusicianone.ticketing_backend.order.OrderService;

import io.github.themusicianone.ticketing_backend.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class PaymentOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeService.class);

    private final OrderService orderService;

    public PaymentOutcomeService(OrderService orderService) {
        this.orderService = orderService;
    }

    @Transactional
    public void apply(Long orderId, ProviderStatus status) {
        switch (status) {
            case PAID -> {
                ConfirmResult result = orderService.confirmOrder(orderId);
                if (result == ConfirmResult.NEEDS_REFUND) {
                    log.warn("Order {} was paid after it ended and needs a refund", orderId);
                }
            }
            case FAILED -> orderService.failOrder(orderId, OrderStatus.FAILED);
            case CANCELED, EXPIRED -> orderService.failOrder(orderId, OrderStatus.EXPIRED);
            case OPEN -> { }
        }
    }
}