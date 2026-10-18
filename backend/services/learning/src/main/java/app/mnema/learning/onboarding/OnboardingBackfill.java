package app.mnema.learning.onboarding;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.Slice;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The milestone backfill (OB-302, queue {@value #QUEUE}): a long-time user sees what they already did. A system job on the ordinary framework; {@link BackfillSchedule} enqueues it once
 * per registry version. For each milestone with a restorable source ({@link BackfillRepository#SOURCES}, registry order, {@code RHYTHM_WEEK} has none) it walks the owners of the
 * source in order, {@code learning.onboarding.backfill.batch} accounts per statement, and merges the history into {@code account_milestone} as {@code source='BACKFILL'} without
 * ever lowering or re-sourcing a row, and without a notification.
 *
 * <p>A slice is time-bounded ({@code slice-budget}): it takes the job's try-lock (an advisory transaction lock, so two workers never backfill at once; a worker that does not get it
 * waits), runs batches until the budget is spent, and saves the cursor {@code {key, after}} in the job row, so a killed or restarted run continues where the last committed slice
 * stopped. Slices are paced by {@code pause}. The slice is the executor's transaction: its rows, its cursor and, after the commit, its metrics belong together.
 * Metrics: {@code mnema_onboarding_backfill_seconds} per slice, {@code mnema_onboarding_backfill_rows_total{key}} and {@code mnema_path_milestone_total{key,source="BACKFILL"}}
 * (the milestones the backfill achieved). Logs carry keys and counts, never an account.
 */
@Component
public class OnboardingBackfill implements JobHandler {
    /** The queue of the backfill job. */
    public static final String QUEUE = "onboarding.backfill";
    private static final Logger LOG = LoggerFactory.getLogger(OnboardingBackfill.class);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(30);

    private final BackfillRepository repository;
    private final BackfillSettings settings;
    private final MeterRegistry meters;

    @Autowired
    OnboardingBackfill(BackfillRepository repository, BackfillSettings settings, MeterRegistry meters) {
        this.repository = repository;
        this.settings = settings;
        this.meters = meters;
    }

    @Override
    public String queue() {
        return QUEUE;
    }

    @Override
    public Slice run(Job job) {
        return run(job, Integer.MAX_VALUE);
    }

    /** One slice that also stops after {@code maxBatches} statements, whatever the clock says: how a test interrupts a run at a known place. */
    Slice run(Job job, int maxBatches) {
        Timer.Sample sample = Timer.start(meters);
        int index = 0;
        UUID after = BackfillRepository.START;
        if (job.progress() != null) {
            index = indexOf(job.progress().path("key").stringValue(""));
            after = UUID.fromString(job.progress().path("after").stringValue(BackfillRepository.START.toString()));
        }
        if (!repository.lock()) return Slice.proceedAfter(cursor(index, after), job.progressCount(), LOCK_WAIT);

        Instant deadline = Instant.now().plus(settings.sliceBudget);
        long rows = 0;
        long batches = 0;
        Totals totals = new Totals();
        while (index < BackfillRepository.SOURCES.size() && batches < maxBatches && Instant.now().isBefore(deadline)) {
            BackfillRepository.Source source = BackfillRepository.SOURCES.get(index);
            UUID upper = repository.upperBound(source, after, settings.batch);
            if (upper == null) {
                index++;
                after = BackfillRepository.START;
                continue;
            }
            BackfillRepository.Written written = repository.write(source, after, upper);
            totals.add(source.key(), written);
            rows += written.rows();
            batches++;
            after = upper;
        }
        after(totals);
        sample.stop(meters.timer("mnema_onboarding_backfill_seconds"));
        long total = job.progressCount() + rows;
        LOG.info("onboarding_backfill_slice key={} batches={} rows={} total_rows={}", index < BackfillRepository.SOURCES.size() ? BackfillRepository.SOURCES.get(index).key() : "none", batches, rows, total);
        if (index >= BackfillRepository.SOURCES.size()) return Slice.done(total);
        return Slice.proceedAfter(cursor(index, after), total, settings.pause);
    }

    private static int indexOf(String key) {
        for (int index = 0; index < BackfillRepository.SOURCES.size(); index++) {
            if (BackfillRepository.SOURCES.get(index).key().name().equals(key)) return index;
        }
        throw new IllegalStateException("Unknown backfill cursor");
    }

    private static ObjectNode cursor(int index, UUID after) {
        return JsonNodeFactory.instance.objectNode().put("key", BackfillRepository.SOURCES.get(Math.min(index, BackfillRepository.SOURCES.size() - 1)).key().name())
                .put("after", after.toString());
    }

    /** What the slice wrote per milestone; counted only when the slice commits. */
    private static final class Totals {
        private final long[] rows = new long[MilestoneKey.values().length];
        private final long[] achieved = new long[MilestoneKey.values().length];

        void add(MilestoneKey key, BackfillRepository.Written written) {
            rows[key.ordinal()] += written.rows();
            achieved[key.ordinal()] += written.achieved();
        }
    }

    private void after(Totals totals) {
        Runnable count = () -> {
            for (MilestoneKey key : MilestoneKey.values()) {
                if (totals.rows[key.ordinal()] > 0) meters.counter("mnema_onboarding_backfill_rows_total", "key", key.name()).increment(totals.rows[key.ordinal()]);
                if (totals.achieved[key.ordinal()] > 0) {
                    meters.counter("mnema_path_milestone_total", "key", key.name(), "source", "BACKFILL").increment(totals.achieved[key.ordinal()]);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }

    /** A backfill that ends {@code FAILED} or {@code CANCELLED} leaves the rows of its committed slices; the next registry version or an operator's retry runs it again. */
    @Override
    public void onEnded(Job job, JobState state) {
        LOG.warn("onboarding_backfill_ended job_id={} state={}", job.id(), state);
    }
}
