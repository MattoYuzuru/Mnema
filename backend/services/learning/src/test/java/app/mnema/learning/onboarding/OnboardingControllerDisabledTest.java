package app.mnema.learning.onboarding;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A switched-off tour answers its disabled view from the controller: the transactional read, and with it the database, is not touched. */
class OnboardingControllerDisabledTest {
    @Test
    void aDisabledReadNeverReachesTheTransactionalService() {
        OnboardingService service = mock(OnboardingService.class);
        when(service.enabled()).thenReturn(false);
        when(service.disabledView()).thenReturn(OnboardingView.disabled(3));
        OnboardingController controller = new OnboardingController(service, new CanonicalJsonHasher());
        Jwt identity = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString()).build();

        ResponseEntity<JsonNode> response = controller.read(identity, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(OnboardingFixture.CONTRACT.path("views").path("disabled"));
        verify(service, never()).read(any(), anyBoolean());
    }
}
