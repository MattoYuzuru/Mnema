package app.mnema.learning.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns orphaned holds to their balances and trims old counters. Every instance may run it: due rows are claimed with
 * {@code SKIP LOCKED}, so two workers never end the same hold.
 */
@Component
class UsageExpiryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(UsageExpiryWorker.class);
    static final int BATCH_SIZE = 200;
    /** Bounds one tick: 20 full batches end 4,000 holds, the rest waits for the next tick. */
    static final int MAX_BATCHES_PER_TICK = 20;

    private final UsageLedger ledger;
    private final UsageMaintenance maintenance;

    UsageExpiryWorker(UsageLedger ledger, UsageMaintenance maintenance) {
        this.ledger = ledger;
        this.maintenance = maintenance;
    }

    @Scheduled(initialDelayString = "${learning.usage.expiry-initial-delay:PT1M}",
            fixedDelayString = "${learning.usage.expiry-interval:PT1M}")
    void sweep() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int ended = ledger.expireDue(BATCH_SIZE);
            total += ended;
            if (ended < BATCH_SIZE) break;
        }
        int counters = maintenance.purgeOldCounters();
        if (total != 0 || counters != 0) {
            LOG.info("Usage maintenance completed expired_holds={} purged_counters={}", total, counters);
        }
    }
}
