package app.mnema.learning.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class NotificationRetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationRetentionWorker.class);
    /** Bounds one tick: 20 full batches clear 10,000 expired rows, the rest waits for the next tick. */
    static final int MAX_BATCHES_PER_TICK = 20;
    private final NotificationRetentionService service;

    NotificationRetentionWorker(NotificationRetentionService service) { this.service = service; }

    @Scheduled(initialDelayString = "${learning.notifications.cleanup-initial-delay:PT5M}",
            fixedDelayString = "${learning.notifications.cleanup-interval:PT1H}")
    void purge() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int deleted = service.purgeBatch();
            total += deleted;
            if (deleted < NotificationRetentionService.BATCH_SIZE) break;
        }
        if (total != 0) LOG.info("Notification retention batch completed expired={}", total);
    }
}
