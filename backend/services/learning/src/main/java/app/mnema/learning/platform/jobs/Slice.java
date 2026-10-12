package app.mnema.learning.platform.jobs;

import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * What one call of {@link JobHandler#run} achieved. The executor writes it, fenced by the lease, in the same transaction as the slice itself.
 */
public sealed interface Slice {
    /**
     * More work remains: {@code progress} is the opaque cursor the next slice resumes from (an object of at most 16 KiB) and {@code progressCount} the
     * figure shown to the learner (rows copied so far). A non-zero {@code delay} PACES the job (a throttled fan-out): the cursor is committed, the lease is given
     * back and the job is queued again {@code delay} later, without spending an attempt and without holding a connection or a thread meanwhile; with no delay the executor
     * runs the next slice at once.
     */
    record Continue(ObjectNode progress, long progressCount, Duration delay) implements Slice {
        public Continue {
            JobJson.write(progress, "progress");
            if (progressCount < 0) throw new IllegalArgumentException("progressCount must not be negative");
            if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("delay must be between zero and an hour");
        }

        public Continue(ObjectNode progress, long progressCount) {
            this(progress, progressCount, Duration.ZERO);
        }
    }

    /** The job is complete; the job ends {@code SUCCEEDED} in the transaction of this slice, so its effects and its end commit together. */
    record Done(long progressCount) implements Slice {
        public Done {
            if (progressCount < 0) throw new IllegalArgumentException("progressCount must not be negative");
        }
    }

    static Slice proceed(ObjectNode progress, long progressCount) {
        return new Continue(progress, progressCount);
    }

    /** As {@link #proceed}, and the next slice runs no earlier than {@code delay} from now (paced work; the lease is released meanwhile). */
    static Slice proceedAfter(ObjectNode progress, long progressCount, Duration delay) {
        return new Continue(progress, progressCount, delay);
    }

    static Slice done(long progressCount) {
        return new Done(progressCount);
    }
}
