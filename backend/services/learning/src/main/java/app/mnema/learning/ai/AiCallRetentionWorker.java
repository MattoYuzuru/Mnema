package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Deletes journal rows older than the retention (90 days by default) in bounded batches. */
@Component
class AiCallRetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AiCallRetentionWorker.class);
    static final int BATCH_SIZE = 500;
    /** Bounds one tick: 20 full batches clear 10,000 rows, the rest waits for the next tick. */
    static final int MAX_BATCHES_PER_TICK = 20;

    private final JdbcCallJournal journal;
    private final Duration retention;

    AiCallRetentionWorker(JdbcCallJournal journal, @Value("${learning.ai.call-retention:P90D}") Duration retention) {
        if (retention.compareTo(Duration.ofDays(1)) < 0 || retention.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Invalid learning.ai.call-retention");
        }
        this.journal = journal;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "${learning.ai.cleanup-initial-delay:PT10M}",
            fixedDelayString = "${learning.ai.cleanup-interval:PT6H}")
    void purge() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int deleted = journal.purgeBatch(BATCH_SIZE, retention);
            total += deleted;
            if (deleted < BATCH_SIZE) break;
        }
        if (total != 0) LOG.info("ai_call_retention completed deleted={}", total);
    }
}
