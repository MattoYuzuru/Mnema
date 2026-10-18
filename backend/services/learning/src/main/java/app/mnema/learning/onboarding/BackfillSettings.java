package app.mnema.learning.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code learning.onboarding.backfill.*} (OB-302): the switch and the knobs of the milestone backfill job. The queue's own kill switch is
 * {@code learning.jobs.onboarding.backfill.enabled}. Invalid values stop the startup.
 * <ul>
 *   <li>{@code enabled} [false]: whether the schedule enqueues the job (a job that exists keeps running or waiting; the queue switch stops it)</li>
 *   <li>{@code batch} [1000, 100-5000]: accounts per statement</li>
 *   <li>{@code slice-budget} [PT3S, PT0.5S-PT20S]: the time after which a slice stops starting batches and saves its cursor</li>
 *   <li>{@code pause} [PT1S, 0-PT1M]: the pause between two slices, so that the backfill never keeps a connection and the tables busy back to back</li>
 *   <li>{@code initial-delay} [PT1M] and {@code schedule-interval} [PT5M]: the tick of the enqueuer</li>
 * </ul>
 */
@Component
final class BackfillSettings {
    final boolean enabled;
    final int batch;
    final Duration sliceBudget;
    final Duration pause;

    BackfillSettings(@Value("${learning.onboarding.backfill.enabled:false}") boolean enabled,
                     @Value("${learning.onboarding.backfill.batch:1000}") int batch,
                     @Value("${learning.onboarding.backfill.slice-budget:PT3S}") Duration sliceBudget,
                     @Value("${learning.onboarding.backfill.pause:PT1S}") Duration pause) {
        if (batch < 100 || batch > 5000) throw new IllegalArgumentException("Invalid onboarding backfill settings: batch must be 100-5000");
        if (sliceBudget == null || sliceBudget.compareTo(Duration.ofMillis(500)) < 0 || sliceBudget.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new IllegalArgumentException("Invalid onboarding backfill settings: slice-budget must be PT0.5S-PT20S");
        }
        if (pause == null || pause.isNegative() || pause.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Invalid onboarding backfill settings: pause must be 0-PT1M");
        }
        this.enabled = enabled;
        this.batch = batch;
        this.sliceBudget = sliceBudget;
        this.pause = pause;
    }
}
