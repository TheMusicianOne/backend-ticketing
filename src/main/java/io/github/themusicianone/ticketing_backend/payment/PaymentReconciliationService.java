package io.github.themusicianone.ticketing_backend.payment;

import io.github.themusicianone.ticketing_backend.order.Order;
import io.github.themusicianone.ticketing_backend.order.OrderRepository;
import io.github.themusicianone.ticketing_backend.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(1);
    private static final int BATCH_SIZE = 100;

    // inject OrderRepository, PaymentGateway, PaymentOutcomeService
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentOutcomeService paymentOutcomeService;

    public PaymentReconciliationService(OrderRepository orderRepository, PaymentGateway paymentGateway,PaymentOutcomeService paymentOutcomeService) {
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
        this.paymentOutcomeService = paymentOutcomeService;
    }

    /** Not @Transactional: it calls the provider for each order. */
    public int reconcile(Instant now) {
        List<Order> orders = orderRepository.findPendingToReconcile(
                OrderStatus.PENDING, now.minus(GRACE_PERIOD), PageRequest.of(0, BATCH_SIZE));

        int resolved = 0;
        for (Order order : orders) {
            try {
                if (reconcileOne(order, now)) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                // One failing order must not stop the rest
                log.warn("Could not reconcile order {}: {}", order.getId(), e.getMessage());
            }
        }
        return resolved;
    }

    private boolean reconcileOne(Order order, Instant now) {
        ProviderStatus status;
        try {
            status = paymentGateway.getStatus(order.getPaymentId());
        } catch (PaymentNotFoundException e) {
            if (now.isBefore(order.getPaymentDeadline())) {
                return false; // maybe a temporary problem, so try again next time
            }
            log.warn("Expiring order {}: {}", order.getId(), e.getMessage());
            paymentOutcomeService.apply(order.getId(), ProviderStatus.EXPIRED);
            return true;
        }
        if (status == ProviderStatus.OPEN) {
            if (now.isBefore(order.getPaymentDeadline())) {
                return false; // the customer may still be paying
            }
            if (!paymentGateway.cancel(order.getPaymentId())) {
                // It stopped being open just now, so ask again
                status = paymentGateway.getStatus(order.getPaymentId());
            } else {
                status = ProviderStatus.CANCELED;
            }
        }

        paymentOutcomeService.apply(order.getId(), status);
        return true;
    }
}