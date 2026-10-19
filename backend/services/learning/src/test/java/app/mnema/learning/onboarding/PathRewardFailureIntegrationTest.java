package app.mnema.learning.onboarding;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An issuer that fails (OB-402): the read is still answered, without a reward; the code, the reward row and the notification are one transaction, so nothing is left behind; the
 * failure is counted and the next read issues.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "learning.onboarding.reward.enabled=true"})
class PathRewardFailureIntegrationTest extends PathRewardSupport {
    @MockitoBean private NotificationPublisher publisher;
    @Autowired private OnboardingService onboarding;

    @Test
    void aFailureOfTheLastWriteLeavesNothingAndDoesNotFailTheReadAndTheNextReadIssues() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        double failed = outcome("failed");
        double issued = outcome("issued");
        when(publisher.publish(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("the notification center is down"));

        HttpResponse<String> response = get(account);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode view = json(response.body());
        assertThat(view.path("enabled").booleanValue()).isTrue();
        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(codes(account)).as("the code rolled back with the rest").isZero();
        assertThat(rewards(account)).isZero();
        assertThat(outcome("failed") - failed).isEqualTo(1.0);
        assertThat(outcome("issued") - issued).isZero();

        reset(publisher);
        when(publisher.publish(any(), any(), any(), any(), any())).thenReturn(true);

        JsonNode next = view(account);

        assertThat(next.path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(codes(account)).isOne();
        assertThat(rewards(account)).isOne();
        assertThat(outcome("issued") - issued).isEqualTo(1.0);
        // the notification is the contract's: kind, dedupe key, route and exactly the end of the offer
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> params = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(account), eq(NotificationKind.PATH_REWARD_READY), eq("path:reward"), params.capture(), eq(NotificationRoute.PATH));
        assertThat(params.getValue()).containsOnlyKeys("expiresAt");
        assertThat(((Instant) params.getValue().get("expiresAt")).toString()).isEqualTo(next.path("reward").path("expiresAt").stringValue(null));
    }

    @Test
    void theServiceReadItselfDoesNotThrowWhenTheIssuerFails() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        when(publisher.publish(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));

        JsonNode view = onboarding.read(account, false, null, Boolean.TRUE);

        assertThat(view.path("reward").isNull()).isTrue();
        assertThat(rewards(account)).isZero();
    }
}
