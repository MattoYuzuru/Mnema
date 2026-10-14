package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared world of the catalogue route tests (Community/2, /6 and /7): the real servlet stack, the optional-bearer chain with a signing Identity, the catalogue and public switches
 * on and the limits high, on a database of its own (the tests read EVERY listed row: facets, totals, searches). All subclasses use this one Spring context. A test starts from an empty
 * catalogue and writes the rows it needs with {@link CatalogSeed}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class CatalogHttpTest extends IsolatedCatalogDatabase {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode CONTRACT = contract();
    static final UUID CONSOLE = UUID.randomUUID();

    @LocalServerPort protected int port;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected CatalogService service;
    protected CatalogSeed seed;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.catalog.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.admin.owner-id", CONSOLE::toString);
    }

    @BeforeEach
    void emptyCatalogue() {
        reset(jdbc);
        jdbc.sql("DELETE FROM app_learning.deck_access_grant").update();
        service.invalidate();
        seed = new CatalogSeed(jdbc, transactions);
    }

    static JsonNode contract() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/community/catalog.json"))) root = root.getParent();
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/community/catalog.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    protected HttpResponse<String> get(String path) {
        return HttpIdentityFixture.send(port, "GET", path, null, null, Map.of());
    }

    protected HttpResponse<String> get(String path, UUID account) {
        return HttpIdentityFixture.send(port, "GET", path, HttpIdentityFixture.reader(account), null, Map.of());
    }

    protected HttpResponse<String> admin(String method, String path, String body, Map<String, String> headers) {
        return HttpIdentityFixture.send(port, method, path, HttpIdentityFixture.adminToken(CONSOLE), body, headers);
    }

    protected static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (RuntimeException failure) {
            throw new AssertionError(response.body(), failure);
        }
    }

    protected JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.headers().allValues("vary")).anySatisfy(value -> assertThat(value).containsIgnoringCase("authorization"));
        return json(response);
    }

    protected static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    /** {@code /public/community/decks?...}: the pairs are name, value, name, value (values are percent-encoded here). */
    protected static String decks(String... pairs) {
        StringBuilder path = new StringBuilder("/public/community/decks");
        for (int index = 0; index < pairs.length; index += 2) {
            path.append(index == 0 ? '?' : '&').append(pairs[index]).append('=').append(URLEncoder.encode(pairs[index + 1], StandardCharsets.UTF_8));
        }
        return path.toString();
    }

    protected static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.path("decks").forEach(card -> titles.add(card.path("title").stringValue(null)));
        return titles;
    }

    protected static List<String> codes(JsonNode page) {
        List<String> codes = new ArrayList<>();
        page.path("decks").forEach(card -> codes.add(card.path("code").stringValue(null)));
        return codes;
    }

    protected static JsonNode cursor(String encoded) {
        return JSON.readTree(Base64.getUrlDecoder().decode(encoded));
    }
}
