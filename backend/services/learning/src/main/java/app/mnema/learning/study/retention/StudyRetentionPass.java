package app.mnema.learning.study.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * One pass of the Study retention worker: the same bounded loop for the expiry steps (raw responses, replay and practice outcomes,
 * pair checks, «Новое» marks, AI answers) and for the snapshot purge. It repeats while some step used its whole batch and the pass
 * has time left ({@code max-run}), so the work keeps pace with the write rate instead of the old one batch per hour.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
public class StudyRetentionPass {
    private static final Logger LOG = LoggerFactory.getLogger(StudyRetentionPass.class);
    private static final long GAUGE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** A hard stop for the loop, whatever the clock says. */
    private static final int MAX_ROUNDS = 10_000;

    private final StudyRetentionService expiry;
    private final StudySnapshotRetention snapshots;
    private final StudySnapshotRepository snapshotRepository;
    private final StudyRetentionSettings settings;
    private final MeterRegistry meters;

    StudyRetentionPass(StudyRetentionService expiry, StudySnapshotRetention snapshots, StudySnapshotRepository snapshotRepository,
                       StudyRetentionSettings settings, MeterRegistry meters) {
        this.expiry = expiry;
        this.snapshots = snapshots;
        this.snapshotRepository = snapshotRepository;
        this.settings = settings;
        this.meters = meters;
        gauge("mnema_study_retention_backlog_sessions", () -> snapshotRepository.backlog(settings.snapshotHorizon()));
        gauge("mnema_study_retention_oldest_unpurged_age_seconds", snapshotRepository::oldestAgeSeconds);
    }

    /** What one pass did, for the log and the tests. */
    public record Result(int rounds, int sessions, int rows, boolean capped) { }

    public Result run() {
        long started = System.nanoTime();
        long deadline = started + settings.maxRun().toNanos();
        int rounds = 0;
        int sessions = 0;
        int rows = 0;
        boolean more;
        int swept = snapshots.sweepGenerations();
        meters.counter("mnema_study_retention_sessions_total", "outcome", "generation_evicting").increment(swept);
        do {
            rounds++;
            more = false;
            StudyRetentionService.PurgeResult expired = expiry.purgeBatch();
            count("raw_response", expired.rawResponses());
            count("compact_outcome", expired.compactOutcomes());
            count("pair_interaction", expired.pairInteractions());
            count("new_mark", expired.newMarks());
            count("assessment_answer", expired.assessmentAnswers());
            rows += expired.total();
            more |= expired.saturated(settings.rowBatch());

            StudySnapshotRetention.SessionBatch batch = snapshots.purgeSessions();
            sessions += batch.sessions();
            meters.counter("mnema_study_retention_sessions_total", "outcome", "purged").increment(batch.sessions());
            meters.counter("mnema_study_retention_sessions_total", "outcome", "generation_evicting").increment(batch.generationsEvicting());
            count("presentation", batch.presentations());
            count("exposure", batch.exposures());
            count("hint_reveal", batch.hints());
            count("transcript_accommodation", batch.transcripts());
            count("assessment", batch.assessments());
            count("pair_interaction", batch.pairs());
            count("start_receipt_compacted", batch.receipts());
            rows += batch.presentations() + batch.exposures() + batch.hints() + batch.transcripts() + batch.assessments()
                    + batch.pairs() + batch.receipts();
            more |= batch.sessions() >= settings.sessionBatch();

            StudySnapshotRetention.Eviction eviction = snapshots.evictChunk();
            count("candidate", eviction.candidates());
            count("candidate_generation", eviction.generationRemoved() ? 1 : 0);
            rows += eviction.rows();
            more |= eviction.rows() > 0;
        } while (more && rounds < MAX_ROUNDS && System.nanoTime() < deadline);
        boolean capped = more;
        long elapsed = System.nanoTime() - started;
        meters.timer("mnema_study_retention_pass_seconds").record(elapsed, TimeUnit.NANOSECONDS);
        meters.counter("mnema_study_retention_passes_total", "outcome", capped ? "capped" : "completed").increment();
        if (rows > 0 || sessions > 0) {
            LOG.info("Study retention pass completed rounds={} sessions={} rows={} capped={} elapsedMs={}", rounds, sessions, rows,
                    capped, TimeUnit.NANOSECONDS.toMillis(elapsed));
        }
        return new Result(rounds, sessions, rows, capped);
    }

    void failed(RuntimeException failure) {
        meters.counter("mnema_study_retention_errors_total").increment();
        meters.counter("mnema_study_retention_passes_total", "outcome", "failed").increment();
        LOG.warn("Study retention pass failed errorType={}", failure.getClass().getSimpleName());
    }

    private void count(String table, int rows) {
        if (rows > 0) meters.counter("mnema_study_retention_rows_total", "table", table).increment(rows);
    }

    /** Read from the table when scraped, at most every 5 s; a database that cannot be read reports NaN, never a false 0. */
    private void gauge(String name, DoubleSupplier read) {
        double[] cached = {0};
        long[] at = {System.nanoTime() - GAUGE_CACHE_NANOS - 1};
        Gauge.builder(name, () -> {
            synchronized (cached) {
                if (System.nanoTime() - at[0] > GAUGE_CACHE_NANOS) {
                    try {
                        cached[0] = read.getAsDouble();
                    } catch (RuntimeException unavailable) {
                        cached[0] = Double.NaN;
                    }
                    at[0] = System.nanoTime();
                }
                return cached[0];
            }
        }).strongReference(true).register(meters);
    }
}
