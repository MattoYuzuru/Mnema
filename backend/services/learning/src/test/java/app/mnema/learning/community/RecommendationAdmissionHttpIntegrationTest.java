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
    "learning.community.for-you.enabled=true", "learning.community.catalog.enabled=true", "learning.community.public-routes.enabled=true", "learning.community.public-routes.account-per-minute=2"
})
class RecommendationAdmissionHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @DynamicPropertySource static void identity(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
    }

    @Test void signedInRecommendationsShareCatalogueAccountBudgetBeforeDatabaseWork() {
        String token = HttpIdentityFixture.reader(UUID.randomUUID());
        assertThat(HttpIdentityFixture.send(port, "GET", "/community/recommendations", token, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(HttpIdentityFixture.send(port, "GET", "/community/recommendations", token, null, Map.of()).statusCode()).isEqualTo(200);
        var throttled = HttpIdentityFixture.send(port, "GET", "/community/recommendations", token, null, Map.of());
        assertThat(throttled.statusCode()).isEqualTo(429);
        assertThat(throttled.headers().firstValue("Retry-After")).isPresent();
    }
}
