package io.github.themusicianone.ticketing_backend.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "app.payments.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class PaymentReconciliationJob {

    // inject PaymentReconciliationService, plus a logger
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);
    private final PaymentReconciliationService reconciliationService;

    public PaymentReconciliationJob(PaymentReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${app.payments.reconciliation.interval:60s}",
            initialDelayString = "${app.payments.reconciliation.initial-delay:30s}")
    void reconcile() {
        int resolved = reconciliationService.reconcile(Instant.now());
        if (resolved > 0) {
            log.info("Reconciled {} orders", resolved);
        }
    }
}