package app.mnema.learning.usage;

import java.util.Locale;

/**
 * A limit a user can run into. {@code CREDITS} is the bar; {@code DAILY_BURST} is the debit limit of one day (a
 * scheduling signal, never an admission failure); the rest are the fair-use buckets and count caps that live outside
 * the bar and are consumed with {@link UsageLedger#consume}.
 */
public enum Bucket {
    CREDITS(Unit.CREDITS, "credits"),
    DAILY_BURST(Unit.CREDITS, "dailyBurst"),
    STT(Unit.MINUTES, "stt"),
    ASSESSMENT(Unit.COUNT, "assessment"),
    PODCASTS(Unit.COUNT, "podcasts"),
    QUALITY_IMAGES(Unit.COUNT, "qualityImages"),
    HIGH_FACTCHECK(Unit.COUNT, "highFactcheck"),
    SMART_PLAN(Unit.COUNT, "smartPlan");

    private final Unit unit;
    private final String capName;

    Bucket(Unit unit, String capName) {
        this.unit = unit;
        this.capName = capName;
    }

    /** The unit of limits and problem members; the stored amount of {@link #STT} is in seconds. */
    public Unit unit() {
        return unit;
    }

    /** The key of the rate card's {@code cap} fields and of the usage response ({@code qualityImages}). */
    String capName() {
        return capName;
    }

    /** Buckets that {@link UsageLedger#consume} counts: fair-use and count caps. */
    boolean consumable() {
        return this != CREDITS && this != DAILY_BURST;
    }

    /** Count caps of the response's {@code caps} object. */
    boolean isCap() {
        return consumable() && this != STT && this != ASSESSMENT;
    }

    static Bucket ofCap(String name) {
        for (Bucket bucket : values()) {
            if (bucket.capName.equals(name)) return bucket;
        }
        throw new IllegalArgumentException("Unknown cap bucket " + name.toLowerCase(Locale.ROOT));
    }
}
