package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/3 (#450): the anomaly routes of the owner console over the real servlet stack and a real PostgreSQL, with the examples of {@code contracts/community/catalog.json}
 * ({@code anomalies}) executed as they are written: the list answers EXACTLY the contract's response for the two flags of the example, the dismissal answers the contract's status.
 * Access is the console owner on the admin client and nobody else; the dismissal is idempotent by command id and appends one audit row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogAnomalyAdminHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode CONTRACT = contract().path("anomalies");
    private static final UUID CONSOLE = UUID.randomUUID();
    private static final String BASE = "/admin/community/anomalies";

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.admin.owner-id", CONSOLE::toString);
    }

    private static JsonNode contract() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/community/catalog.json"))) root = root.getParent();
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/community/catalog.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @BeforeEach
    void flags() {
        jdbc.sql("DELETE FROM app_learning.catalog_anomaly").update();
        for (JsonNode entry : CONTRACT.path("list").path("response").path("entries")) seed(entry);
    }

    /** Writes the flag exactly as the contract's example shows it (the deck is no longer public: the last known code and title are what the console shows). */
    private UUID seed(JsonNode entry) {
        UUID deck = UUID.randomUUID();
        Instant dismissed = entry.path("dismissedAt").isNull() ? null : Instant.parse(entry.path("dismissedAt").stringValue(null));
        jdbc.sql("""
                INSERT INTO app_learning.catalog_anomaly(deck_id, public_code, title, owner_id, detected_at, window_forks, new_share, dismissed_at)
                VALUES (:deck, :code, :title, :owner, :detected, :forks, :share, :dismissed)
                """).param("deck", deck).param("code", entry.path("code").stringValue(null)).param("title", entry.path("title").stringValue(null))
                .param("owner", UUID.fromString(entry.path("ownerId").stringValue(null))).param("detected", Timestamp.from(Instant.parse(entry.path("detectedAt").stringValue(null))))
                .param("forks", entry.path("windowForks").intValue()).param("share", new java.math.BigDecimal(entry.path("newShare").asString()))
                .param("dismissed", dismissed == null ? null : Timestamp.from(dismissed)).update();
        return deck;
    }

    private HttpResponse<String> call(String method, String path, String bearer, String body) {
        return HttpIdentityFixture.send(port, method, path, bearer, body, Map.of());
    }

    private HttpResponse<String> admin(String method, String path, String body) {
        return call(method, path, HttpIdentityFixture.adminToken(CONSOLE), body);
    }

    private static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (RuntimeException failure) {
            throw new AssertionError(response.body(), failure);
        }
    }

    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE action = :a").param("a", action).query(Long.class).single();
    }

    private static String dismissBody(String command) {
        return "{\"commandId\":\"" + command + "\"}";
    }

    // ---- the contract's examples ----

    @Test
    void theListAnswersExactlyTheExampleOfTheContractWithTheOpenFlagBeforeTheDismissedOne() {
        HttpResponse<String> response = admin("GET", BASE, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        assertThat(json(response)).isEqualTo(CONTRACT.path("list").path("response"));
    }

    @Test
    void theDismissalOfTheContractsExampleAnswersItsStatusAndIsIdempotentByCommandId() {
        // the contract writes the public path; the fixture adds the /api context itself
        String path = CONTRACT.path("dismiss").path("request").stringValue(null).substring("POST /api".length());
        String body = CONTRACT.path("dismiss").path("body").toString();
        String command = CONTRACT.path("dismiss").path("body").path("commandId").stringValue(null);
        assertThat(path).isEqualTo(BASE + "/Kq7xT3mNpR:dismiss");
        long before = audits("COMMUNITY_ANOMALY_DISMISS");

        HttpResponse<String> first = admin("POST", path, body);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(CONTRACT.path("dismiss").path("status").intValue()).isEqualTo(204);
        assertThat(first.body()).isEmpty();
        assertThat(first.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(audits("COMMUNITY_ANOMALY_DISMISS")).isEqualTo(before + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE action = 'COMMUNITY_ANOMALY_DISMISS' AND command_id = :c AND actor_account_id = :a")
                .param("c", UUID.fromString(command)).param("a", CONSOLE).query(Long.class).single()).isEqualTo(1);

        // the same command again: the same answer, no second write, no second audit row
        assertThat(admin("POST", path, body).statusCode()).isEqualTo(204);
        assertThat(audits("COMMUNITY_ANOMALY_DISMISS")).isEqualTo(before + 1);
        // another command for the flag that is already dismissed: nothing to change, nothing to audit
        assertThat(admin("POST", path, dismissBody(UUID.randomUUID().toString())).statusCode()).isEqualTo(204);
        assertThat(audits("COMMUNITY_ANOMALY_DISMISS")).isEqualTo(before + 1);
        // the same command for another flag is a reused command id
        HttpResponse<String> reused = admin("POST", BASE + "/Zp4WcA8vQe:dismiss", body);
        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(json(reused).path("code").stringValue(null)).isEqualTo("IDEMPOTENCY_CONFLICT");

        // the list now shows the dismissal and keeps the open flags first: both are dismissed, newest detection first
        JsonNode entries = json(admin("GET", BASE, null)).path("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path("code").stringValue(null)).isEqualTo("Kq7xT3mNpR");
        assertThat(entries.get(0).path("dismissedAt").isString()).isTrue();
        assertThat(entries.get(1).path("code").stringValue(null)).isEqualTo("Zp4WcA8vQe");
        assertThat(entries.get(1).path("dismissedAt").stringValue(null)).isEqualTo("2026-10-06T08:15:00Z");
    }

    @Test
    void anUnknownOrMalformedCodeIsTheOpaque404AndWritesNothing() {
        long before = audits("COMMUNITY_ANOMALY_DISMISS");
        for (String code : List.of("Aaaaaaaaaa", "short", "has%20space", "0OIl000000", "Kq7xT3mNpRR")) {
            HttpResponse<String> response = admin("POST", BASE + "/" + code + ":dismiss", dismissBody(UUID.randomUUID().toString()));
            assertThat(response.statusCode()).as(code).isEqualTo(404);
        }
        assertThat(audits("COMMUNITY_ANOMALY_DISMISS")).isEqualTo(before);
    }

    @Test
    void theBodyIsExactlyACommandIdAndNothingElse() {
        String path = BASE + "/Kq7xT3mNpR:dismiss";
        String good = UUID.randomUUID().toString();
        for (String body : List.of("{}", "{\"commandId\":\"" + good + "\",\"extra\":1}", "{\"commandId\":7}", "{\"commandId\":null}", "{\"commandId\":\"not-a-uuid\"}",
                "{\"commandId\":\"00000000-0000-0000-0000-000000000000\"}", "{\"commandId\":\"" + good.toUpperCase(java.util.Locale.ROOT) + "x\"}", "[]", "not json", "")) {
            assertThat(admin("POST", path, body).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_anomaly WHERE dismissed_at IS NULL").query(Long.class).single()).isEqualTo(1);
    }

    // ---- access ----

    @Test
    void onlyTheConsoleOwnerOnTheAdminClientMayReadOrDismiss() {
        UUID stranger = UUID.randomUUID();
        List<String[]> routes = List.of(new String[] {"GET", BASE}, new String[] {"POST", BASE + "/Kq7xT3mNpR:dismiss"});
        for (String[] route : routes) {
            String body = route[0].equals("POST") ? dismissBody(UUID.randomUUID().toString()) : null;
            String label = route[0] + " " + route[1];
            assertThat(call(route[0], route[1], null, body).statusCode()).as(label).isEqualTo(401);
            assertThat(call(route[0], route[1], HttpIdentityFixture.reader(stranger), body).statusCode()).as(label + " learner").isEqualTo(403);
            assertThat(call(route[0], route[1], HttpIdentityFixture.reader(CONSOLE), body).statusCode()).as(label + " owner on the learner client").isEqualTo(403);
            assertThat(call(route[0], route[1], HttpIdentityFixture.adminToken(stranger), body).statusCode()).as(label + " admin client of another account").isEqualTo(403);
        }
        // nothing changed
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_anomaly WHERE dismissed_at IS NULL").query(Long.class).single()).isEqualTo(1);
        assertThat(audits("COMMUNITY_ANOMALY_DISMISS")).isZero();
    }

    // ---- paging ----

    @Test
    void theListPagesFiftyAtATimeOpenFlagsFirstThenDismissedOnesNewestFirstWithoutGapsOrRepeats() {
        List<String> expected = new ArrayList<>();
        Instant base = Instant.parse("2026-11-01T00:00:00Z");
        for (int index = 0; index < 55; index++) {
            String code = code(index);
            jdbc.sql("""
                    INSERT INTO app_learning.catalog_anomaly(deck_id, public_code, title, owner_id, detected_at, window_forks, new_share, dismissed_at)
                    VALUES (:deck, :code, 'Колода', :owner, :detected, 30, 0.9, NULL)
                    """).param("deck", UUID.randomUUID()).param("code", code).param("owner", UUID.randomUUID()).param("detected", Timestamp.from(base.plusSeconds(index))).update();
            expected.add(0, code);
        }
        // the two flags of the contract's example come after them: the open one is older than these 55, the dismissed one is last
        expected.add("Kq7xT3mNpR");
        expected.add("Zp4WcA8vQe");

        JsonNode first = json(admin("GET", BASE, null));
        assertThat(first.path("entries")).hasSize(50);
        assertThat(first.path("next").isString()).isTrue();
        JsonNode second = json(admin("GET", BASE + "?cursor=" + first.path("next").stringValue(null), null));
        assertThat(second.path("entries")).hasSize(7);
        assertThat(second.path("next").isNull()).isTrue();
        List<String> seen = new ArrayList<>();
        first.path("entries").forEach(entry -> seen.add(entry.path("code").stringValue(null)));
        second.path("entries").forEach(entry -> seen.add(entry.path("code").stringValue(null)));
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(new HashSet<>(seen)).hasSize(57);

        // a cursor is opaque and strict
        String cursor = first.path("next").stringValue(null);
        for (String bad : List.of("x", "AAAA", cursor + "A", cursor.substring(1), "%00", "a b")) {
            assertThat(admin("GET", BASE + "?cursor=" + bad.replace(" ", "%20"), null).statusCode()).as(bad).isEqualTo(400);
        }
        assertThat(admin("GET", BASE + "?limit=5", null).statusCode()).isEqualTo(400);
        assertThat(admin("GET", BASE + "?cursor=" + cursor + "&cursor=" + cursor, null).statusCode()).isEqualTo(400);
    }

    @Test
    void theSetOfFieldsAndTheStatusesOfTheContractAreTheOnesOfTheCode() {
        Set<String> fields = new java.util.TreeSet<>();
        CONTRACT.path("list").path("response").path("entries").get(0).propertyNames().forEach(fields::add);
        assertThat(fields).containsExactly("code", "detectedAt", "dismissedAt", "newShare", "ownerId", "title", "windowForks");
        Set<String> actual = new java.util.TreeSet<>();
        json(admin("GET", BASE, null)).path("entries").get(0).propertyNames().forEach(actual::add);
        assertThat(actual).isEqualTo(fields);
        assertThat(CONTRACT.path("rules").stringValue(null)).contains("50 per page").contains("idempotent by commandId").contains("one admin_audit row");
    }

    private static String code(int index) {
        String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        StringBuilder text = new StringBuilder("Fx");
        int value = index;
        for (int digit = 0; digit < 8; digit++) {
            text.append(alphabet.charAt(value % alphabet.length()));
            value /= alphabet.length();
        }
        return text.toString();
    }
}
