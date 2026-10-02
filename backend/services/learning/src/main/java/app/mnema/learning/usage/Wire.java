package app.mnema.learning.usage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Wire formatting shared by the usage responses: UTC RFC 3339 with seconds, and half-up percentages. */
final class Wire {
    private Wire() { }

    static String time(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** {@code part / whole} as an integer percent, rounded half up and clamped to 0..100; 0 for an empty whole. */
    static int percent(long part, long whole) {
        if (whole <= 0) return 0;
        return (int) Math.min(100, (200 * part + whole) / (2 * whole));
    }
}
