package app.mnema.learning.platform.jobs;

import tools.jackson.databind.node.ObjectNode;

/**
 * What one call of {@link JobHandler#run} achieved. The executor writes it, fenced by the lease, in the same transaction as the slice itself.
 */
public sealed interface Slice {
    /**
     * More work remains: {@code progress} is the opaque cursor the next slice resumes from (an object of at most 16 KiB) and {@code progressCount} the
     * figure shown to the learner (rows copied so far).
     */
    record Continue(ObjectNode progress, long progressCount) implements Slice {
        public Continue {
            JobJson.write(progress, "progress");
            if (progressCount < 0) throw new IllegalArgumentException("progressCount must not be negative");
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

    static Slice done(long progressCount) {
        return new Done(progressCount);
    }
}
