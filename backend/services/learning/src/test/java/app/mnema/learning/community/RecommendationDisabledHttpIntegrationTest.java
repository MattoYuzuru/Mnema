package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecommendationDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @DynamicPropertySource static void identity(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
    }

    @Test void defaultOffIs404BeforeMalformedQueryBodyAndVersionButGuestStillNeedsAuthentication() {
        UUID account = UUID.randomUUID();
        for (String route : List.of("/community/interests?bad=1", "/community/recommendations?limit=0&sort=unknown")) {
            assertThat(HttpIdentityFixture.send(port, "GET", route, HttpIdentityFixture.reader(account), null, Map.of()).statusCode()).isEqualTo(404);
            assertThat(HttpIdentityFixture.send(port, "GET", route, null, null, Map.of()).statusCode()).isEqualTo(401);
        }

        for (String route : List.of("/community/events", "/community/exposures", "/community/interests:reset"))
        assertThat(HttpIdentityFixture.send(port, "POST", route, HttpIdentityFixture.reader(account), "{bad", Map.of("If-Match", "bad")).statusCode()).isEqualTo(404);
        assertThat(HttpIdentityFixture.send(port, "PUT", "/community/interests", HttpIdentityFixture.reader(account), "[]", Map.of("If-Match", "bad")).statusCode()).isEqualTo(404);
    }
}
