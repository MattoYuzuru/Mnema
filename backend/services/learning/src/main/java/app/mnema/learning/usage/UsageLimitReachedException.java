package app.mnema.learning.usage;

import app.mnema.learning.platform.api.ProblemExtension;

import java.time.Instant;
import java.util.Objects;

/**
 * An admission or consumption does not fit a bucket: {@code 409 USAGE_LIMIT_REACHED}. Thrown before any state change;
 * the transaction of the caller rolls back with it.
 */
public final class UsageLimitReachedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * The members of the problem and of an estimate's {@code blockingBuckets} entry.
     *
     * @param limit            the limit of the bucket in this window, null when unlimited within fair use
     * @param used             used or held in this window, in {@code unit}
     * @param required         what the operation needs, in {@code unit}
     * @param offered          false when the plan has no such bucket (a cap of 0): waiting never helps
     * @param renewsAt         the next refresh or unlock, null when waiting will not help
     * @param fitsAfterRenewal whether {@code required} fits the bucket after {@code renewsAt}
     */
    public record Block(Bucket bucket, Window window, Unit unit, Long limit, long used, long required, boolean offered,
                        Instant renewsAt, boolean fitsAfterRenewal, Plan plan) {
        public Block {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(plan, "plan");
        }

        ProblemExtension toExtension() {
            return ProblemExtension.builder().put("bucket", bucket).put("window", window).put("unit", unit)
                    .put("limit", limit).put("used", used).put("required", required).put("offered", offered)
                    .put("renewsAt", renewsAt).put("fitsAfterRenewal", fitsAfterRenewal).put("plan", plan).build();
        }
    }

    private final transient Block block;

    public UsageLimitReachedException(Block block) {
        super("Usage limit reached", null, false, false);
        this.block = Objects.requireNonNull(block, "block");
    }

    public Block block() {
        return block;
    }

    /** The problem's extension members. */
    public ProblemExtension extension() {
        return block.toExtension();
    }
}
