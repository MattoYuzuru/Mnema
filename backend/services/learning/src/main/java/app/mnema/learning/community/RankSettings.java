package app.mnema.learning.community;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code learning.community.rank.*}: the constants of the rank v1 formula (RFC 11.2) and the retention of generations. They are STARTING HEURISTICS to be tuned by evidence, not
 * contract; the defaults are the numbers of the RFC. Invalid values fail at startup.
 *
 * <pre>
 * c_u   = trust_u x (fork_u + activatedWeight x activated_u) x 2^(-days_since_event / halfLifeDays)     over the non-owner accounts with an event in the last windowDays
 * S     = sum c_u
 * Q     = (activated + priorWeight x priorRate) / (forks + priorWeight)
 * fresh = freshFloor + (1 - freshFloor) x 2^(-days_since_content_update / freshHalfLifeDays)
 * score = ln(1 + S) x (0.5 + Q) x fresh
 * </pre>
 *
 * <ul>
 *   <li>{@code enabled} [true] the kill switch of the hourly scheduling (a generation that exists stays readable)</li>
 *   <li>windowDays {@code window-days} [30, 1-365] the span in which a copy or a study session counts for S and "learning now"</li>
 *   <li>halfLifeDays {@code half-life-days} [14, 1-365] the half-life of an event's weight</li>
 *   <li>activationDays {@code activation-days} [3, 1-30] the distinct study days that make a copy "activated"</li>
 *   <li>forkWeight {@code fork-weight} [1.0] the weight of a copy in S</li>
 *   <li>activatedWeight {@code activated-weight} [2.0] the weight of an activation in S</li>
 *   <li>priorWeight {@code prior-weight} [10, 1-1000] the pseudo-copies of the Bayesian average of Q</li>
 *   <li>priorRate {@code prior-rate} [0.25, 0-1] the activation rate assumed for them</li>
 *   <li>freshFloor {@code fresh-floor} [0.6, 0-1] the multiplier of a deck whose content is very old</li>
 *   <li>freshHalfLifeDays {@code fresh-half-life-days} [120, 1-3650]</li>
 *   <li>retention {@code retention} [PT24H, PT1H-P30D] how long a completed generation is kept after its completion (the newest completed one is always kept)</li>
 * </ul>
 */
@Component
final class RankSettings {
    final boolean enabled;
    final int windowDays;
    final double halfLifeDays;
    final int activationDays;
    final double forkWeight;
    final double activatedWeight;
    final double priorWeight;
    final double priorRate;
    final double freshFloor;
    final double freshHalfLifeDays;
    final Duration retention;

    RankSettings(@Value("${learning.community.rank.enabled:true}") boolean enabled,
                 @Value("${learning.community.rank.window-days:30}") int windowDays,
                 @Value("${learning.community.rank.half-life-days:14}") double halfLifeDays,
                 @Value("${learning.community.rank.activation-days:3}") int activationDays,
                 @Value("${learning.community.rank.fork-weight:1.0}") double forkWeight,
                 @Value("${learning.community.rank.activated-weight:2.0}") double activatedWeight,
                 @Value("${learning.community.rank.prior-weight:10}") double priorWeight,
                 @Value("${learning.community.rank.prior-rate:0.25}") double priorRate,
                 @Value("${learning.community.rank.fresh-floor:0.6}") double freshFloor,
                 @Value("${learning.community.rank.fresh-half-life-days:120}") double freshHalfLifeDays,
                 @Value("${learning.community.rank.retention:PT24H}") Duration retention) {
        if (windowDays < 1 || windowDays > 365 || !between(halfLifeDays, 1, 365) || activationDays < 1 || activationDays > 30
                || !between(forkWeight, 0, 100) || !between(activatedWeight, 0, 100) || !between(priorWeight, 1, 1000) || !between(priorRate, 0, 1)
                || !between(freshFloor, 0, 1) || !between(freshHalfLifeDays, 1, 3650)
                || retention == null || retention.compareTo(Duration.ofHours(1)) < 0 || retention.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid learning.community.rank settings");
        }
        this.enabled = enabled;
        this.windowDays = windowDays;
        this.halfLifeDays = halfLifeDays;
        this.activationDays = activationDays;
        this.forkWeight = forkWeight;
        this.activatedWeight = activatedWeight;
        this.priorWeight = priorWeight;
        this.priorRate = priorRate;
        this.freshFloor = freshFloor;
        this.freshHalfLifeDays = freshHalfLifeDays;
        this.retention = retention;
    }

    /** The RFC defaults, for tests and tools. */
    static RankSettings defaults() {
        return new RankSettings(true, 30, 14, 3, 1.0, 2.0, 10, 0.25, 0.6, 120, Duration.ofHours(24));
    }

    private static boolean between(double value, double min, double max) {
        return !Double.isNaN(value) && value >= min && value <= max;
    }
}
