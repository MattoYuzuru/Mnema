package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** With {@code learning.onboarding.reward.enabled} off (the default) nothing is issued, nothing of the reward is read, and an existing reward is not shown (OB-402). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z"})
class PathRewardOffIntegrationTest extends PathRewardSupport {
    @Autowired private OnboardingService onboarding;

    @Test
    void aCompleteTrustedAccountWithAnEnabledCampaignIsNotIssued() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        double issued = outcome("issued");
        double none = outcome("no_campaign");

        assertThat(view(account).path("reward").isNull()).isTrue();
        assertThat(view(account).path("reward").isNull()).isTrue();

        assertThat(codes(account)).isZero();
        assertThat(rewards(account)).isZero();
        assertThat(announcements(account)).isZero();
        assertThat(outcome("issued") - issued).isZero();
        assertThat(outcome("no_campaign") - none).isZero();
    }

    @Test
    void anExistingRewardIsNotShownAndTheViewDoesNotEvenReadIt() {
        UUID account = trusted();
        complete(account);
        insertReward(account, discountCampaign(10));

        StatementCounting.start();
        var view = onboarding.read(account, false, null, Boolean.TRUE);
        List<String> statements = StatementCounting.stop();

        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(view.has("reward")).as("the member is always present").isTrue();
        assertThat(statements.stream().filter(sql -> sql.contains("onboarding_reward") || sql.contains("promo_code"))).isEmpty();
        // the reward is still the account's: switching the flag back on shows it, nothing is revoked
        assertThat(rewards(account)).isOne();
    }
}
