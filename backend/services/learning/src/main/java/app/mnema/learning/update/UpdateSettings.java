package app.mnema.learning.update;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code learning.updates.*} (Updates/2, #441, the combined publication of a copy's update).
 * {@code sync-max-units} [100, 1-1000] is the largest command {@link CombinedPublication#publish} runs inside the request; a bigger one is {@link CombinedPublication#enqueue}d.
 * {@code max-units} [10000, sync-max-units..32767] is the largest command at all (items + exercises + objectives + metadata): the journal ordinal of one revision is a SMALLINT and the
 * final transaction of a job has to stay short. {@code slice-size} [200, 10-1000] is the number of page edits a job slice applies. {@code staging-lease} [PT30M, PT1M-PT1H] is the lease of the
 * staging pins that keep the pages a job has written until its last slice (renewed by every slice). Invalid values fail at startup. The queue itself is {@code learning.jobs.deck.combined.publish.*}.
 */
@Component
public final class UpdateSettings {
    final int syncMaxUnits;
    final int maxUnits;
    final int sliceSize;
    final Duration stagingLease;

    UpdateSettings(@Value("${learning.updates.sync-max-units:100}") int syncMaxUnits,
                   @Value("${learning.updates.max-units:10000}") int maxUnits,
                   @Value("${learning.updates.slice-size:200}") int sliceSize,
                   @Value("${learning.updates.staging-lease:PT30M}") Duration stagingLease) {
        if (syncMaxUnits < 1 || syncMaxUnits > 1_000 || maxUnits < syncMaxUnits || maxUnits > 32_767 || sliceSize < 10 || sliceSize > 1_000
                || stagingLease == null || stagingLease.compareTo(Duration.ofMinutes(1)) < 0 || stagingLease.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid learning.updates settings");
        }
        this.syncMaxUnits = syncMaxUnits;
        this.maxUnits = maxUnits;
        this.sliceSize = sliceSize;
        this.stagingLease = stagingLease;
    }

    public int syncMaxUnits() { return syncMaxUnits; }

    public int maxUnits() { return maxUnits; }
}
