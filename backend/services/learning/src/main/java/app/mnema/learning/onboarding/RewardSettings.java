package app.mnema.learning.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The path reward (OB-402, ADR-ONB-005; {@code contracts/onboarding} «Path reward»):
 * <ul>
 *   <li>{@code learning.onboarding.reward.enabled} (default false): the global kill switch, with {@code learning.onboarding.enabled}. Off, nothing is issued, the view's
 *       {@code reward} is null and no read of the reward is made; the owner's campaign switch ({@code promo_campaign.enabled}) is the console's one. Codes already issued are
 *       not revoked by either switch.</li>
 *   <li>{@code learning.onboarding.reward.daily-limit} (default 200, 1 to 10000): at most this many codes per campaign in any 24 hours, the rate limit against account farms;
 *       beyond it nothing is issued now and the next read of a qualifying account tries again. A value outside the range stops startup.</li>
 * </ul>
 */
@Component
final class RewardSettings {
    static final int MAX_DAILY_LIMIT = 10_000;

    private final boolean enabled;
    private final int dailyLimit;

    RewardSettings(@Value("${learning.onboarding.reward.enabled:false}") boolean enabled, @Value("${learning.onboarding.reward.daily-limit:200}") int dailyLimit) {
        if (dailyLimit < 1 || dailyLimit > MAX_DAILY_LIMIT) {
            throw new IllegalArgumentException("Invalid onboarding settings: reward.daily-limit must be between 1 and " + MAX_DAILY_LIMIT);
        }
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
    }

    boolean enabled() {
        return enabled;
    }

    int dailyLimit() {
        return dailyLimit;
    }
}
