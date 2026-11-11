package io.github.themusicianone.ticketing_backend.hold;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.holds.expiry-job.enabled",
        havingValue = "true",
        matchIfMissing = true)
class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldService holdService;

    HoldExpiryJob(HoldService holdService) {
        this.holdService = holdService;
    }

    @Scheduled(
            fixedDelayString = "${app.holds.expiry-job.interval:30s}",
            initialDelayString = "${app.holds.expiry-job.initial-delay:10s}")
    void expireHolds() {
        int expired = holdService.expireDueHolds(Instant.now());
        if (expired > 0) {
            log.info("Expired {} holds", expired);
        }
    }
}