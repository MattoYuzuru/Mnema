package app.mnema.core.review.algorithm;

public record CanonicalProgress(
        double difficulty01,
        double stabilityDays
) {
    public CanonicalProgress {
        difficulty01 = clamp01(difficulty01);
        stabilityDays = Math.max(0.1, stabilityDays);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
