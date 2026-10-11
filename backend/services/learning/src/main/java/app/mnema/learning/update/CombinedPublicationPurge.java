package app.mnema.learning.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer of {@link CombinedPublication#purgeOrphans}: it removes, a bounded batch per pass, the stored commands that no live job holds any more (a queued job cancelled by
 * {@code LearningJobs.cancel} runs no hook; a job purged by retention; a lost enqueue race). Only for the {@code worker} and {@code all} roles, like the other timers.
 * {@code learning.updates.purge-interval} [PT10M] is the pause between passes and {@code learning.updates.purge-batch} [200] the commands removed per pass. An {@code enqueue}
 * also removes a few, so the table cannot grow while the timer is off.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class CombinedPublicationPurge {
    private static final Logger LOG = LoggerFactory.getLogger(CombinedPublicationPurge.class);
    private final CombinedPublication publication;
    private final int batch;

    CombinedPublicationPurge(CombinedPublication publication, @org.springframework.beans.factory.annotation.Value("${learning.updates.purge-batch:200}") int batch) {
        if (batch < 1 || batch > 10_000) throw new IllegalArgumentException("Invalid learning.updates.purge-batch");
        this.publication = publication;
        this.batch = batch;
    }

    @Scheduled(initialDelayString = "${learning.updates.purge-interval:PT10M}", fixedDelayString = "${learning.updates.purge-interval:PT10M}")
    void run() {
        try {
            int purged = publication.purgeOrphans(batch);
            if (purged > 0) LOG.info("deck_combined_publication_purged commands={}", purged);
        } catch (RuntimeException failure) {
            LOG.warn("deck_combined_publication_purge_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
