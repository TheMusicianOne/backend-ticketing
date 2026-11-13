package io.github.themusicianone.ticketing_backend.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payments.refund.enabled", havingValue = "true", matchIfMissing = true)
public class RefundJob {


    private static final Logger log = LoggerFactory.getLogger(RefundJob.class);
    private final RefundService refundService;
    public RefundJob(RefundService refundService) {
        this.refundService = refundService;
    }

    @Scheduled(fixedDelayString = "${app.payments.refund.interval:60s}",
            initialDelayString = "${app.payments.refund.initial-delay:30s}")
    void refund() {
        int refunded = refundService.processRefunds();
        if (refunded > 0) {
            log.info("Processed {} refunds", refunded);
        }
    }
}
