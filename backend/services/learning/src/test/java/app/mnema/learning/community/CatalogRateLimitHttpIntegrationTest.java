package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits of the public reads apply to the catalogue like to a deck read ({@code contracts/decks/public-read.json}): a guest has a budget per client network that every route of the
 * catalogue and the sitemap draw on together, the sixth request of a minute is 429 {@code RATE_LIMITED} with {@code Retry-After}, a signed-in viewer is limited by account in a table of
 * his own and is not refused because guests exhausted theirs, and a refused request reads nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogRateLimitHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.catalog.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 5);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 7);
    }

    @Test
    void guestsShareOneBudgetAcrossEveryRouteAndSignedInViewersHaveTheirOwn() {
        String[] routes = {"/public/community/facets", "/public/community/shelves", "/public/community/decks?q=x", "/public/community/decks", "/public/sitemap.xml"};
        for (String route : routes) assertThat(HttpIdentityFixture.send(port, "GET", route, null, null, Map.of()).statusCode()).as(route).isEqualTo(200);
        for (String route : routes) {
            HttpResponse<String> refused = HttpIdentityFixture.send(port, "GET", route, null, null, Map.of());
            assertThat(refused.statusCode()).as(route).isEqualTo(429);
            assertThat(JSON.readTree(refused.body()).path("code").stringValue(null)).isEqualTo("RATE_LIMITED");
            assertThat(refused.headers().firstValue("retry-after")).hasValueSatisfying(value -> assertThat(Integer.parseInt(value)).isBetween(1, 60));
            assertThat(refused.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        }
        // a refused guest has nothing to do with an account
        UUID account = UUID.randomUUID();
        for (int request = 0; request < 7; request++) {
            assertThat(HttpIdentityFixture.send(port, "GET", "/public/community/decks?q=x", HttpIdentityFixture.reader(account), null, Map.of()).statusCode()).isEqualTo(200);
        }
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/community/decks?q=x", HttpIdentityFixture.reader(account), null, Map.of()).statusCode()).isEqualTo(429);
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/community/authors/" + UUID.randomUUID() + "/decks", HttpIdentityFixture.reader(UUID.randomUUID()), null, Map.of()).statusCode())
                .as("another account has its own budget").isEqualTo(200);
        // an invalid request counts like any other
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/community/decks?limit=0", HttpIdentityFixture.reader(UUID.randomUUID()), null, Map.of()).statusCode()).isEqualTo(400);
    }
}
