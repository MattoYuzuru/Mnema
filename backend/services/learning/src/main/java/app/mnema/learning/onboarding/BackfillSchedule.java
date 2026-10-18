package app.mnema.learning.onboarding;

import app.mnema.learning.platform.jobs.LearningJobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The enqueuer of the milestone backfill (OB-302), like {@code CatalogSchedule} only for the {@code worker} and {@code all} roles. While
 * {@code learning.onboarding.backfill.enabled} is on it enqueues {@value OnboardingBackfill#QUEUE} with the dedupe key of the registry version
 * ({@code onboarding.backfill:v1}): one job per version however many instances and restarts, and a new registry version runs the backfill again. A process remembers the key it
 * enqueued, so the steady state is no statement; a failed enqueue is retried at the next tick. Off, it does nothing.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class BackfillSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(BackfillSchedule.class);

    private final LearningJobs jobs;
    private final BackfillSettings settings;
    private final AtomicReference<String> last = new AtomicReference<>();

    @Autowired
    BackfillSchedule(LearningJobs jobs, BackfillSettings settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    static String dedupeKey() {
        return OnboardingBackfill.QUEUE + ":v" + MilestoneKey.REGISTRY_VERSION;
    }

    @Scheduled(initialDelayString = "${learning.onboarding.backfill.initial-delay:PT1M}", fixedDelayString = "${learning.onboarding.backfill.schedule-interval:PT5M}")
    void tick() {
        if (!settings.enabled) return;
        String key = dedupeKey();
        if (key.equals(last.get())) return;
        try {
            jobs.enqueue(OnboardingBackfill.QUEUE, key, null, JsonNodeFactory.instance.objectNode().put("registryVersion", MilestoneKey.REGISTRY_VERSION));
            last.set(key);
        } catch (RuntimeException failure) {
            LOG.warn("onboarding_backfill_enqueue_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
