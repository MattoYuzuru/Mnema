package app.mnema.learning.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link SessionRetention} on a schedule ({@code learning.generation.retention.interval}), like
 * {@code StudyRetentionWorker}. Only for the {@code worker} and {@code all} roles: an {@code api} process serves requests
 * and never sweeps.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class RetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(RetentionWorker.class);
    private final SessionRetention retention;
    private final IntentUses intents;

    RetentionWorker(SessionRetention retention, IntentUses intents) {
        this.retention = retention;
        this.intents = intents;
    }

    @Scheduled(initialDelayString = "${learning.generation.retention.interval:PT10M}",
            fixedDelayString = "${learning.generation.retention.interval:PT10M}")
    void sweep() {
        try {
            retention.run();
        } catch (RuntimeException failure) {
            LOG.warn("generation_retention_failed error_type={}", failure.getClass().getSimpleName());
        }
        try {
            intents.purge();
        } catch (RuntimeException failure) {
            LOG.warn("generation_intent_purge_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
