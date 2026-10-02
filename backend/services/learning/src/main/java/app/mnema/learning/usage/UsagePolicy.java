package app.mnema.learning.usage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Tunable usage policy ({@code learning.usage.*}); every value of the owner decisions of 2026-10-02 lives in one key.
 * Invalid configuration fails at startup.
 */
@Component
final class UsagePolicy {
    final String rateCardVersion;
    final ZoneId zone;
    final BigDecimal dailyBurstFraction;
    final List<Integer> freeWeeklyPortions;
    final int lowThresholdPercent;
    final Duration reservationTtl;

    UsagePolicy(@Value("${learning.usage.rate-card-version:rc-v1}") String rateCardVersion,
                @Value("${learning.usage.calendar-zone:Europe/Moscow}") String zone,
                @Value("${learning.usage.daily-burst-fraction:0.35}") BigDecimal dailyBurstFraction,
                @Value("${learning.usage.free-weekly-portions:13,13,12,12}") String freeWeeklyPortions,
                @Value("${learning.usage.low-threshold-percent:80}") int lowThresholdPercent,
                @Value("${learning.usage.reservation-ttl:PT2H}") Duration reservationTtl) {
        if (!rateCardVersion.matches("rc-v[0-9]{1,3}")) throw invalid("rate card version");
        this.rateCardVersion = rateCardVersion;
        try {
            this.zone = ZoneId.of(zone);
        } catch (DateTimeException failure) {
            throw invalid("calendar zone");
        }
        if (dailyBurstFraction.signum() <= 0 || dailyBurstFraction.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("daily burst fraction");
        }
        this.dailyBurstFraction = dailyBurstFraction.stripTrailingZeros();
        try {
            this.freeWeeklyPortions = Arrays.stream(freeWeeklyPortions.split(",")).map(String::trim)
                    .map(Integer::parseInt).toList();
        } catch (NumberFormatException failure) {
            throw invalid("free weekly portions");
        }
        if (this.freeWeeklyPortions.isEmpty() || this.freeWeeklyPortions.size() > 5
                || this.freeWeeklyPortions.stream().anyMatch(portion -> portion < 1)) {
            throw invalid("free weekly portions");
        }
        // 90 and 100 are fixed; the configured first threshold must stay below them.
        if (lowThresholdPercent < 50 || lowThresholdPercent > 89) throw invalid("low threshold");
        this.lowThresholdPercent = lowThresholdPercent;
        // The hold of a run is bounded by PT1H (learning.generation.step.max-run); a shorter TTL would expire live holds.
        if (reservationTtl.compareTo(Duration.ofMinutes(1)) < 0 || reservationTtl.compareTo(Duration.ofDays(1)) > 0
                || reservationTtl.getNano() != 0) {
            throw invalid("reservation ttl");
        }
        this.reservationTtl = reservationTtl;
    }

    /** USAGE_LOW thresholds in ascending order. */
    List<Integer> lowThresholds() {
        return List.of(lowThresholdPercent, 90, 100);
    }

    private static IllegalArgumentException invalid(String what) {
        return new IllegalArgumentException("Invalid usage policy: " + what);
    }
}
