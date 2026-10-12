package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kill switch of the catalogue: {@code learning.community.catalog.enabled} ships {@code false}, and then every public route of the contract and the sitemap answer 404 as if absent,
 * whatever the query, the bearer or the method of a HEAD; nothing is parsed, read or counted against the limit. The owner's editorial routes keep working. The switch is also a child of the
 * switch of the public routes: with that one off the catalogue is off too, even when its own is on ({@link ParentOff}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID CONSOLE = UUID.randomUUID();
    static final List<String> ROUTES = List.of("/public/community/facets", "/public/community/shelves", "/public/community/decks", "/public/community/decks?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9",
            "/public/community/decks?limit=0", "/public/community/decks?sort=nonsense&q=x", "/public/community/decks?cursor=%40%40", "/public/community/authors/0198a6c0-1d3e-7a52-9b0c-2f6d8e4a1c35/decks",
            "/public/community/authors/not-a-uuid/decks", "/public/sitemap.xml");

    @LocalServerPort private int port;
    @Autowired private Environment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.admin.owner-id", CONSOLE::toString);
    }

    static void assertAbsent(int port) {
        // the 404 of a path nobody serves under the same chain: the disabled routes must look exactly like it
        HttpResponse<String> unmapped = HttpIdentityFixture.send(port, "GET", "/public/community/nothing-here", null, null, Map.of());
        assertThat(unmapped.statusCode()).isEqualTo(404);
        for (String path : ROUTES) {
            for (String bearer : new String[] {null, HttpIdentityFixture.reader(UUID.randomUUID())}) {
                HttpResponse<String> response = HttpIdentityFixture.send(port, "GET", path, bearer, null, Map.of());
                assertThat(response.statusCode()).as(path).isEqualTo(404);
                assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
                JsonNode body = JSON.readTree(response.body());
                assertThat(body.path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
                assertThat(anonymised(response)).isEqualTo(anonymised(unmapped));
            }
        }
        // an invalid bearer is still 401: authentication is before the switch, like on the deck routes
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/community/facets", "not-a-token", null, Map.of()).statusCode()).isEqualTo(401);
    }

    private static String anonymised(HttpResponse<String> response) {
        ObjectNode body = (ObjectNode) JSON.readTree(response.body());
        body.remove("instance");
        return response.statusCode() + " " + response.headers().firstValue("content-type").orElse("") + " " + response.headers().firstValue("cache-control").orElse("") + " " + body;
    }

    @Test
    void theRoutesShipDisabledAndAnswerLikeAnUnknownPath() {
        assertThat(environment.getProperty("learning.community.catalog.enabled")).isEqualTo("false");
        assertAbsent(port);
    }

    @Test
    void theOwnersEditorialRoutesWorkWhileThePublicOnesAreOff() {
        HttpResponse<String> state = HttpIdentityFixture.send(port, "GET", "/admin/community/editorial", HttpIdentityFixture.adminToken(CONSOLE), null, Map.of());
        assertThat(state.statusCode()).as(state.body()).isEqualTo(200);
        assertThat(JSON.readTree(state.body()).path("entries").isArray()).isTrue();
        HttpResponse<String> cleared = HttpIdentityFixture.send(port, "PUT", "/admin/community/editorial", HttpIdentityFixture.adminToken(CONSOLE),
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"codes\":[]}", Map.of("If-Match", "\"" + JSON.readTree(state.body()).path("rowVersion").stringValue(null) + "\""));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
    }

    /** The catalogue switch on, the switch of the public routes off (its default): still 404. */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    static class ParentOff extends PostgresIntegrationTest {
        @LocalServerPort private int port;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            HttpIdentityFixture.register(registry);
            registry.add("learning.community.catalog.enabled", () -> true);
        }

        @Test
        void theCatalogueFollowsTheSwitchOfThePublicRoutes() {
            assertAbsent(port);
        }
    }
}
