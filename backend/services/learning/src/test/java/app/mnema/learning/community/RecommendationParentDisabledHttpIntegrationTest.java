package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "learning.community.for-you.enabled=true", "learning.community.catalog.enabled=true", "learning.community.public-routes.enabled=false"
})
class RecommendationParentDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @DynamicPropertySource static void identity(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
    }

    @Test void parentPublicRouteSwitchClosesRecommendationLinksBeforeQueryValidation() {
        String token = HttpIdentityFixture.reader(UUID.randomUUID());
        assertThat(HttpIdentityFixture.send(port, "GET", "/community/recommendations?limit=0&sort=unknown", token, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(HttpIdentityFixture.send(port, "GET", "/community/interests", token, null, Map.of()).statusCode()).isEqualTo(200);
    }
}
