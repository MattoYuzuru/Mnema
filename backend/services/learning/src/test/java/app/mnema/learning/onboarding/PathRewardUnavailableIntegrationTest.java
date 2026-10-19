package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** In production without a usable promo secret promo codes are off (PromoSettings); then no reward is issued, and the outcome is counted (OB-402). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "learning.onboarding.reward.enabled=true",
        "APP_ENV=prod", "learning.promo.hash-secret="})
class PathRewardUnavailableIntegrationTest extends PathRewardSupport {
    @Test
    void withoutThePromoSecretNothingIsIssuedAndTheReadsAreStillAnswered() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        double unavailable = outcome("unavailable");

        assertThat(view(account).path("reward").isNull()).isTrue();
        assertThat(view(account).path("reward").isNull()).isTrue();

        assertThat(codes(account)).isZero();
        assertThat(rewards(account)).isZero();
        assertThat(outcome("unavailable") - unavailable).isEqualTo(2.0);
    }
}
