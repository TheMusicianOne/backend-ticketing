package io.github.themusicianone.ticketing_backend.payment;

import io.github.themusicianone.ticketing_backend.order.ConfirmResult;
import io.github.themusicianone.ticketing_backend.order.OrderRepository;
import io.github.themusicianone.ticketing_backend.order.OrderService;

import io.github.themusicianone.ticketing_backend.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;


@Service
public class PaymentOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeService.class);

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public PaymentOutcomeService(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public void apply(Long orderId, ProviderStatus status) {
        switch (status) {
            case PAID -> {
                ConfirmResult result = orderService.confirmOrder(orderId);
                if (result == ConfirmResult.NEEDS_REFUND) {
                    int marked = orderRepository.markRefundRequired(orderId, Instant.now(), List.of(OrderStatus.FAILED, OrderStatus.EXPIRED));
                    if (marked==1) {
                        log.info("Refund required for order {}. Marked in repository", orderId);
                    }
                }
            }
            case REFUNDED -> {}
            case FAILED -> orderService.failOrder(orderId, OrderStatus.FAILED);
            case CANCELED, EXPIRED -> orderService.failOrder(orderId, OrderStatus.EXPIRED);
            case OPEN -> { }
        }
    }
}