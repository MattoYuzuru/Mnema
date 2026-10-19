package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** With the tour switched off the reward is off with it, whatever its own flag says: the view is the disabled one and nothing is issued (OB-402). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"learning.onboarding.enabled=false", "learning.onboarding.reward.enabled=true"})
class PathRewardOnboardingOffIntegrationTest extends PathRewardSupport {
    @Test
    void nothingIsIssuedAndTheViewIsTheDisabledOne() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        double issued = outcome("issued");

        JsonNode view = view(account);

        assertThat(view.path("enabled").booleanValue()).isFalse();
        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(codes(account)).isZero();
        assertThat(rewards(account)).isZero();
        assertThat(outcome("issued") - issued).isZero();
    }
}
