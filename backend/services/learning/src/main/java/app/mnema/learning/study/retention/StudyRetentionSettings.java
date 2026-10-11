package app.mnema.learning.study.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Policy of the Study retention worker (#460), all under {@code learning.study.retention.*} (docs/engineering/runtime-policy-index.md).
 * {@code snapshotHorizon} is a product number: how long an issued presentation and the session around it stay readable. It has a floor of
 * five days. The replay window is the same local date, and {@code ZoneId} accepts offsets up to +-18:00: a source started in UTC+18 and a
 * replay requested in UTC-18 on the same date are up to 60 hours apart, and the replay session itself lives 24 hours more (84 hours);
 * the rest is margin. The rest are engineering bounds of one pass.
 */
@Component
public final class StudyRetentionSettings {
    static final Duration MIN_HORIZON = Duration.ofDays(5);
    static final Duration MAX_HORIZON = Duration.ofDays(90);

    private final boolean enabled;
    private final Duration snapshotHorizon;
    private final int sessionBatch;
    private final int rowBatch;
    private final int generationChunk;
    private final int generationSweep;
    private final Duration maxRun;

    StudyRetentionSettings(
            @Value("${learning.study.retention.enabled:true}") boolean enabled,
            @Value("${learning.study.retention.snapshot-horizon:P7D}") Duration snapshotHorizon,
            @Value("${learning.study.retention.session-batch:100}") int sessionBatch,
            @Value("${learning.study.retention.row-batch:500}") int rowBatch,
            @Value("${learning.study.retention.generation-chunk:2000}") int generationChunk,
            @Value("${learning.study.retention.generation-sweep:100}") int generationSweep,
            @Value("${learning.study.retention.max-run:PT30S}") Duration maxRun,
            @Value("${learning.study.retention.interval:PT5M}") Duration interval,
            @Value("${learning.study.retention.initial-delay:PT2M}") Duration initialDelay) {
        if (snapshotHorizon == null || snapshotHorizon.compareTo(MIN_HORIZON) < 0 || snapshotHorizon.compareTo(MAX_HORIZON) > 0
                || sessionBatch < 1 || sessionBatch > 1000 || rowBatch < 1 || rowBatch > 5000
                || generationChunk < 10 || generationChunk > 10_000 || generationSweep < 1 || generationSweep > 1000
                || maxRun == null || maxRun.compareTo(Duration.ofSeconds(1)) < 0 || maxRun.compareTo(Duration.ofMinutes(5)) > 0
                || interval == null || interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofDays(1)) > 0
                || initialDelay == null || initialDelay.isNegative() || initialDelay.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid Study retention policy");
        }
        this.enabled = enabled;
        this.snapshotHorizon = snapshotHorizon;
        this.sessionBatch = sessionBatch;
        this.rowBatch = rowBatch;
        this.generationChunk = generationChunk;
        this.generationSweep = generationSweep;
        this.maxRun = maxRun;
    }

    public boolean enabled() { return enabled; }

    public Duration snapshotHorizon() { return snapshotHorizon; }

    int sessionBatch() { return sessionBatch; }

    int rowBatch() { return rowBatch; }

    int generationChunk() { return generationChunk; }

    int generationSweep() { return generationSweep; }

    Duration maxRun() { return maxRun; }
}
