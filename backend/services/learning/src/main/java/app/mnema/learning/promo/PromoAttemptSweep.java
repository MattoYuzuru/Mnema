package app.mnema.learning.promo;

import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes redemption attempts older than {@link PromoAttempts#RETENTION} in bounded batches, so the table stays the size of two hours of traffic
 * whoever stops attempting (an account's own rows are otherwise purged only by its next attempt). Runs for the {@code worker} and {@code all} roles
 * ({@code learning.runtime.roles}), like the other sweeps: an {@code api} process only serves requests.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class PromoAttemptSweep {
    private static final Logger LOG = LoggerFactory.getLogger(PromoAttemptSweep.class);
    static final int BATCH_SIZE = 1_000;
    /** Bounds one tick: 20 full batches clear 20,000 rows, the rest waits for the next tick. */
    static final int MAX_BATCHES_PER_TICK = 20;

    private final PromoRepository repository;
    private final UsageClock clock;

    PromoAttemptSweep(PromoRepository repository, UsageClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${learning.promo.attempt-sweep-initial-delay:PT5M}",
            fixedDelayString = "${learning.promo.attempt-sweep-interval:PT15M}")
    void scheduled() {
        try {
            sweep();
        } catch (RuntimeException failure) {
            LOG.warn("promo_attempt_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    /** @return the number of rows deleted by this tick */
    int sweep() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int deleted = repository.purgeAttemptsBefore(clock.now().minus(PromoAttempts.RETENTION), BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) break;
        }
        if (total != 0) LOG.info("promo_attempt_sweep completed deleted={}", total);
        return total;
    }
}
