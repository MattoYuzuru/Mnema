package app.mnema.learning.community;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@code learning.community.anomaly.*}: the daily anomaly query of the owner console (contracts/community/catalog.json, constants {@code anomaly}). A deck is flagged when at least
 * {@code min-forks} [20] copies were made in the last {@code window-days} [7] days and MORE than {@code new-share-above} [0.7] of them by accounts that Identity called new (younger than
 * 48 hours at the copy). {@code enabled} [true] stops the scheduling; {@code run-hour-utc} [3, 0-23] is the hour of the day (UTC) from which the day's job is enqueued. Invalid values fail
 * at startup.
 */
@Component
final class AnomalySettings {
    final boolean enabled;
    final int minForks;
    final int windowDays;
    final BigDecimal newShareAbove;
    final int runHourUtc;

    AnomalySettings(@Value("${learning.community.anomaly.enabled:true}") boolean enabled,
                    @Value("${learning.community.anomaly.min-forks:20}") int minForks,
                    @Value("${learning.community.anomaly.window-days:7}") int windowDays,
                    @Value("${learning.community.anomaly.new-share-above:0.7}") String newShareAbove,
                    @Value("${learning.community.anomaly.run-hour-utc:3}") int runHourUtc) {
        BigDecimal share;
        try {
            share = new BigDecimal(newShareAbove.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Invalid learning.community.anomaly settings");
        }
        if (minForks < 1 || minForks > 100_000 || windowDays < 1 || windowDays > 90 || share.signum() < 0 || share.compareTo(BigDecimal.ONE) >= 0
                || runHourUtc < 0 || runHourUtc > 23) {
            throw new IllegalArgumentException("Invalid learning.community.anomaly settings");
        }
        this.enabled = enabled;
        this.minForks = minForks;
        this.windowDays = windowDays;
        this.newShareAbove = share;
        this.runHourUtc = runHourUtc;
    }

    static AnomalySettings defaults() {
        return new AnomalySettings(true, 20, 7, "0.7", 3);
    }
}
