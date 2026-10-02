package app.mnema.learning.generation.mbm;

/**
 * Bounded work for the inline scanner. Inline rules look ahead (closing backticks, closing emphasis, matching
 * brackets); a hostile source such as a long run of unmatched openers could make that quadratic. The budget is linear
 * in the source size, far above what real text needs, and exceeding it is reported as a too large document.
 */
final class Budget {

    private static final long STEPS_PER_CHARACTER = 64;
    private static final long BASE = 4_096;

    private long remaining;

    Budget(int sourceLength) {
        this.remaining = BASE + STEPS_PER_CHARACTER * sourceLength;
    }

    void spend(int steps) {
        remaining -= steps;
        if (remaining < 0) {
            throw new LimitExceededException();
        }
    }
}
