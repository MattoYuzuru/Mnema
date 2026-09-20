package app.mnema.learning.study.session;

/** Fixed upper bounds for candidate preparation and one presentation selection window. */
final class BoundedCandidatePlanner {
    static final int PREPARATION_LIMIT = 500;
    static final int PRESENTATION_LIMIT = 20;
    static final int SCAN_MULTIPLIER = 4;

    private BoundedCandidatePlanner() { }

    static Window window(long seed, int candidateCount, int remainingBudget) {
        if (candidateCount < 0 || remainingBudget < 0) throw new IllegalArgumentException("Negative count");
        int target = Math.min(PRESENTATION_LIMIT, Math.min(candidateCount, remainingBudget));
        int start = candidateCount == 0 ? 0 : Math.floorMod(seed, candidateCount);
        int scanLimit = Math.min(candidateCount, target * SCAN_MULTIPLIER);
        return new Window(start, target, scanLimit);
    }

    record Window(int start, int target, int scanLimit) { }
}
