package app.mnema.learning.update.plan;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * What ONE plan computation may still spend on reading documents: a deadline that every content fingerprint and every node check consults, and the number of pairs of contents it may
 * still compare ({@link UpdatePlanSettings}). Single-threaded, one per computation. A step that finds the budget spent does not read and answers the cautious way.
 */
final class PlanBudget {
    private final LongSupplier nanoClock;
    private final long deadline;
    private int pairsLeft;

    PlanBudget(Duration time, int pairs, LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.deadline = nanoClock.getAsLong() + time.toNanos();
        this.pairsLeft = pairs;
    }

    /** Whether the time is up. */
    boolean spent() { return nanoClock.getAsLong() - deadline >= 0; }

    /** Takes one pair of contents to compare; false when the time is up or the pairs of this plan are used. */
    boolean takePair() {
        if (pairsLeft <= 0 || spent()) return false;
        pairsLeft--;
        return true;
    }
}
