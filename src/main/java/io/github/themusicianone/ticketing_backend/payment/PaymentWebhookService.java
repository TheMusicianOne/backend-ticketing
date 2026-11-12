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
    private final PaymentOutcomeService paymentOutcomeService;

    public PaymentWebhookService(PaymentWebhookEventRepository pwerepository,OrderService orderService,OrderRepository orderRepository, PaymentOutcomeService paymentOutcomeService) {
        this.pwerepository = pwerepository;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentOutcomeService = paymentOutcomeService;
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
        paymentOutcomeService.apply(orderId, ProviderStatus.fromProvider(event.status()));

    }
}
