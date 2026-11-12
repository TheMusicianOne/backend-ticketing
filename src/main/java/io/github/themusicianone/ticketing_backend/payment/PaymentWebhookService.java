package io.github.themusicianone.ticketing_backend.payment;

import io.github.themusicianone.ticketing_backend.order.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    // inject PaymentWebhookEventRepository, OrderRepository, OrderService
    private final PaymentWebhookEventRepository pwerepository;
    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public PaymentWebhookService(PaymentWebhookEventRepository pwerepository,OrderService orderService,OrderRepository orderRepository) {
        this.pwerepository = pwerepository;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    record PaymentEvent(String eventId, String paymentId, String status) {
    }

    @Transactional
    public void handle(PaymentEvent event) {
        if (!pwerepository.recordIfNew(event.eventId(), event.paymentId())) {
            log.info("Ignoring duplicate webhook {}", event.eventId());
            return;
        }

        Optional<Order> order = orderRepository.findByPaymentId(event.paymentId());
        if (order.isEmpty()) {
            log.warn("Webhook {} for unknown payment {}", event.eventId(), event.paymentId());
            return;
        }

        Long orderId = order.get().getId();
        switch (event.status()) {
            case "paid" -> {
                if (orderService.confirmOrder(orderId) == ConfirmResult.NEEDS_REFUND) {
                    log.warn("Order {} was paid after it ended and needs a refund", orderId);
                }
            }
            case "failed" -> orderService.failOrder(orderId, OrderStatus.FAILED);
            case "canceled", "expired" -> orderService.failOrder(orderId, OrderStatus.EXPIRED);
            default -> log.info("Ignoring status {} for order {}", event.status(), orderId);
        }
    }
}
