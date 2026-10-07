package app.mnema.learning.events;

import app.mnema.learning.support.ContractFixtures;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL and security filters; the Identity fixture implements signed JWT keys and current grant liveness. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventsHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String ISSUER = "https://events-identity.example.test";
    private static final UUID OWNER = UUID.randomUUID();
    private static final RSAKey KEY;
    private static final HttpServer IDENTITY;
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final AtomicInteger IDENTITY_CALLS = new AtomicInteger();
    private static volatile int userInfoStatus = 200;
    private static volatile boolean mismatchedSubject;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("events-test").generate();
            IDENTITY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IDENTITY.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            IDENTITY.createContext("/oauth2/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            IDENTITY.createContext("/userinfo", exchange -> {
                IDENTITY_CALLS.incrementAndGet();
                String subject;
                try {
                    subject = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7))
                            .getJWTClaimsSet().getSubject();
                } catch (java.text.ParseException failure) {
                    subject = "invalid";
                }
                if (mismatchedSubject) subject = UUID.randomUUID().toString();
                byte[] body = ("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(userInfoStatus, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            IDENTITY.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private EventRepository repository;

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        registry.add("learning.identity.issuer", () -> ISSUER);
        registry.add("learning.identity.transport-base", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
        registry.add("learning.identity.allow-loopback-http", () -> true);
        registry.add("learning.events.owner-id", OWNER::toString);
    }

    @BeforeEach
    void cleanEditorialRows() {
        jdbc.sql("DELETE FROM app_learning.product_event").update();
        userInfoStatus = 200;
        mismatchedSubject = false;
        IDENTITY_CALLS.set(0);
    }

    @AfterAll
    static void closeFixture() {
        IDENTITY.stop(0);
        CLIENT.shutdownNow();
    }

    @Test
    void publicTimelineNeedsNoIdentityAndNeverPublishesDraftOrEditorialMetadata() throws Exception {
        repository.create(UUID.randomUUID(), command(UUID.randomUUID(), "Публичное событие", "**Текст** [ссылка](https://mnema.app)", true));
        repository.create(UUID.randomUUID(), command(UUID.randomUUID(), "Закрытый черновик", "private", false));
        userInfoStatus = 500;

        var response = request("GET", "/events", "broken-token", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("public, max-age=60");
        var body = json(response);
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("nextCursor").isNull()).isTrue();
        JsonNode event = body.path("items").get(0);
        assertThat(event.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("eventId", "title", "bodyMarkdown", "eventDate", "publishedAt");
        assertThat(event.path("title").stringValue(null)).isEqualTo("Публичное событие");
        assertThat(event.path("bodyMarkdown").stringValue(null)).isEqualTo("**Текст** [ссылка](https://mnema.app)");
        assertThat(request("HEAD", "/events", null, null, null).statusCode()).isEqualTo(200);
        assertThat(IDENTITY_CALLS).hasValue(0);
        problem(request("POST", "/events", null, "{}", null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void pagesHaveFiftyRowsWithDeterministicTieBreakersAndNewerInsertsDoNotShiftTheNextPage() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 53; i++) {
            UUID id = UUID.randomUUID();
            expected.add(id);
            repository.create(id, command(UUID.randomUUID(), "Событие " + i, "text", true));
        }
        // PostgreSQL UUID ordering is unsigned byte order, unlike UUID.compareTo.
        expected.sort(java.util.Comparator.comparing(UUID::toString).reversed());
        var first = json(request("GET", "/events", null, null, null));
        assertThat(first.path("items")).hasSize(50);
        assertThat(ids(first)).isEqualTo(expected.subList(0, 50));
        String cursor = first.path("nextCursor").stringValue(null);
        repository.create(UUID.randomUUID(), new EventRequests.Command(UUID.randomUUID(), "Позже", "text", LocalDate.of(2026, 10, 8), true));
        var last = json(request("GET", "/events?cursor=" + cursor, null, null, null));
        assertThat(ids(last)).isEqualTo(expected.subList(50, 53));
        assertThat(last.path("nextCursor").isNull()).isTrue();
        var union = new HashSet<>(ids(first));
        union.addAll(ids(last));
        assertThat(union).hasSize(53);

        // A page with exactly fifty rows has no spurious next page.
        for (UUID id : expected.subList(50, 53)) jdbc.sql("DELETE FROM app_learning.product_event WHERE event_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM app_learning.product_event WHERE event_date=DATE '2026-10-08'").update();
        assertThat(json(request("GET", "/events", null, null, null)).path("nextCursor").isNull()).isTrue();
    }

    @Test
    void calendarDatesRoundTripWithoutTimeZoneOrHistoricalCalendarConversion() throws Exception {
        UUID old = UUID.randomUUID();
        UUID future = UUID.randomUUID();
        LocalDate earliest = LocalDate.of(1, 1, 1);
        LocalDate latest = LocalDate.of(9999, 12, 31);
        repository.create(old, new EventRequests.Command(UUID.randomUUID(), "Old", "text", earliest, true));
        repository.create(future, new EventRequests.Command(UUID.randomUUID(), "Future", "text", latest, true));
        var page = json(request("GET", "/events", null, null, null)).path("items");
        assertThat(page.get(0).path("eventDate").stringValue(null)).isEqualTo("9999-12-31");
        assertThat(page.get(1).path("eventDate").stringValue(null)).isEqualTo("0001-01-01");
    }

    @Test
    void onlyTheConfiguredOwnerCanReadOrWriteEvenIfAnotherAccountClaimsAdmin() throws Exception {
        String ownerRead = token(OWNER, "learning.read", false);
        String stranger = token(UUID.randomUUID(), "learning.read learning.write", true);
        problem(request("GET", "/admin/events", null, null, null), 401, "AUTHENTICATION_REQUIRED");
        problem(request("GET", "/admin/events/access", stranger, null, null), 403, "ACCESS_DENIED");
        problem(request("GET", "/admin/events", stranger, null, null), 403, "ACCESS_DENIED");
        problem(request("POST", "/admin/events", stranger, "not-json", null), 403, "ACCESS_DENIED");
        problem(request("PUT", "/admin/events/invalid", stranger, "not-json", null), 403, "ACCESS_DENIED");
        problem(request("DELETE", "/admin/events/invalid", stranger, null, null), 403, "ACCESS_DENIED");
        problem(request("POST", "/admin/events", ownerRead, commandJson(false), null), 403, "ACCESS_DENIED");
        problem(request("GET", "/admin/events", token(OWNER, "learning.write", false), null, null), 403, "ACCESS_DENIED");
        var access = request("GET", "/admin/events/access", ownerRead, null, null);
        assertThat(access.statusCode()).isEqualTo(200);
        assertThat(access.body()).isEqualTo("{\"allowed\":true}");
        assertThat(access.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.product_event").query(Long.class).single()).isZero();
    }

    @Test
    void createPublishUnpublishAndDeleteUseReceiptsAndVersionPreconditions() throws Exception {
        String owner = token(OWNER, "learning.read learning.write", false);
        String createBody = commandJson(false);
        var created = request("POST", "/admin/events", owner, createBody, null);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("etag")).contains("\"0\"");
        assertThat(created.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode initial = json(created).path("event");
        String id = initial.path("eventId").stringValue(null);
        assertThat(initial.path("publishedAt").isNull()).isTrue();
        assertThat(initial.path("eventDate").stringValue(null)).isEqualTo("2026-10-07");
        var duplicate = request("POST", "/admin/events", owner, createBody, null);
        assertThat(json(duplicate)).isEqualTo(json(created));
        assertThat(duplicate.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(duplicate.headers().firstValue("etag")).isEmpty();
        problem(request("POST", "/admin/events", owner, createBody.replace("Заголовок", "Другой"), null), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(json(request("GET", "/events", null, null, null)).path("items")).isEmpty();
        assertThat(json(request("GET", "/admin/events", owner, null, null)).path("items")).hasSize(1);

        String updateBody = commandJson(true);
        problem(request("PUT", "/admin/events/" + id, owner, updateBody, null), 428, "PRECONDITION_REQUIRED");
        var published = request("PUT", "/admin/events/" + id, owner, updateBody, "\"0\"");
        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(published.headers().firstValue("etag")).contains("\"1\"");
        String publication = json(published).path("event").path("publishedAt").stringValue(null);
        assertThat(publication).isNotNull();
        assertThat(json(request("GET", "/events", null, null, null)).path("items")).hasSize(1);
        var updateReplay = request("PUT", "/admin/events/" + id, owner, updateBody, "\"0\"");
        assertThat(updateReplay.statusCode()).isEqualTo(200);
        assertThat(updateReplay.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(json(updateReplay)).isEqualTo(json(published));
        problem(request("PUT", "/admin/events/" + id, owner, commandJson(true), "\"0\""), 412, "VERSION_CONFLICT");

        var unpublished = request("PUT", "/admin/events/" + id, owner, commandJson(false), "\"1\"");
        assertThat(unpublished.statusCode()).isEqualTo(200);
        assertThat(json(unpublished).path("event").path("publishedAt").stringValue(null)).isEqualTo(publication);
        assertThat(json(request("GET", "/events", null, null, null)).path("items")).isEmpty();
        String deletion = "/admin/events/" + id + "?commandId=" + UUID.randomUUID();
        problem(request("DELETE", deletion, owner, null, "\"0\""), 412, "VERSION_CONFLICT");
        var deleted = request("DELETE", deletion, owner, null, "\"2\"");
        assertThat(deleted.statusCode()).isEqualTo(204);
        var replayed = request("DELETE", deletion, owner, null, "\"2\"");
        assertThat(replayed.statusCode()).isEqualTo(204);
        assertThat(replayed.headers().firstValue("idempotency-replayed")).contains("true");
        problem(request("DELETE", "/admin/events/" + id + "?commandId=" + UUID.randomUUID(), owner, null, "\"2\""), 404, "RESOURCE_NOT_FOUND");
        problem(request("PUT", "/admin/events/" + id, owner, commandJson(true), "\"2\""), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void revocationAndIdentityFailureStopOwnerMutationIncludingReceiptReplays() throws Exception {
        String owner = token(OWNER, "learning.read learning.write", false);
        String body = commandJson(true);
        assertThat(request("POST", "/admin/events", owner, body, null).statusCode()).isEqualTo(201);
        userInfoStatus = 401;
        problem(request("POST", "/admin/events", owner, body, null), 401, "AUTHENTICATION_REQUIRED");
        userInfoStatus = 503;
        problem(request("POST", "/admin/events", owner, body, null), 503, "IDENTITY_UNAVAILABLE");
        userInfoStatus = 200;
        mismatchedSubject = true;
        problem(request("POST", "/admin/events", owner, body, null), 503, "IDENTITY_UNAVAILABLE");
        assertThat(IDENTITY_CALLS).hasValue(4);
        assertThat(request("GET", "/events", null, null, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.product_event").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesApplyOnceAndConcurrentDifferentReplacementsCannotLoseAnEdit() throws Exception {
        String owner = token(OWNER, "learning.read learning.write", false);
        String body = commandJson(true);
        var create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/events"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + owner).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var first = CLIENT.sendAsync(create, HttpResponse.BodyHandlers.ofString());
        var second = CLIENT.sendAsync(create, HttpResponse.BodyHandlers.ofString());
        var created = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
        var retried = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(json(created)).isEqualTo(json(retried));
        assertThat(List.of(created, retried).stream().filter(result -> result.headers().firstValue("idempotency-replayed").isPresent()))
                .hasSize(1);
        String id = json(created).path("event").path("eventId").stringValue(null);
        var replacements = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (String title : List.of("Первый", "Второй")) {
            var update = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/events/" + id))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + owner).header("Content-Type", "application/json")
                    .header("If-Match", "\"0\"").PUT(HttpRequest.BodyPublishers.ofString(commandJson(true).replace("Заголовок", title))).build();
            replacements.add(CLIENT.sendAsync(update, HttpResponse.BodyHandlers.ofString()));
        }
        var outcomes = List.of(replacements.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS),
                replacements.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertThat(outcomes).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 412);
        var accepted = outcomes.stream().filter(result -> result.statusCode() == 200).findFirst().orElseThrow();
        var current = json(request("GET", "/admin/events", owner, null, null)).path("items").get(0);
        assertThat(current.path("title")).isEqualTo(json(accepted).path("event").path("title"));
        assertThat(current.path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.product_event").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void malformedInputsStayBoundedAndReturnStableProblemsWithoutWriting() throws Exception {
        String owner = token(OWNER, "learning.read learning.write", false);
        String valid = commandJson(false);
        for (String invalid : List.of("not json", "[]", "{}", valid + "{}", valid.replace("false", "\"false\""),
                valid.replace("Заголовок", " "), valid.replace("Заголовок", " Заголовок"), valid.replace("Заголовок", "x".repeat(161)),
                valid.replace("Текст", " "), valid.replace("Текст", "x".repeat(16001)), valid.replace("Текст", "\\u0000"),
                valid.replace("2026-10-07", "2026-02-30"), valid.replace("2026-10-07", "0000-01-01"),
                valid.replace("2026-10-07", "2026-1-01"), valid.replace("\"commandId\":", "\"commandId\":null,\"commandId\":"),
                valid.replaceFirst("\\}$", ",\"unexpected\":1}"), "{\"padding\":\"" + "x".repeat(65536) + "\"}")) {
            problem(request("POST", "/admin/events", owner, invalid, null), 400, "INVALID_REQUEST");
        }
        for (String query : List.of("?cursor=", "?cursor=bad", "?cursor=" + "x".repeat(81), "?cursor=bad&cursor=bad", "?limit=51")) {
            problem(request("GET", "/events" + query, null, null, null), 400, "INVALID_REQUEST");
        }
        String id = UUID.randomUUID().toString();
        for (String version : List.of("0", "*", "W/\"0\"", "\"01\"", "\"9223372036854775807\"", "\"9999999999999999999\"", "\"0\",\"1\"")) {
            problem(request("PUT", "/admin/events/" + id, owner, valid, version), 400, "INVALID_REQUEST");
        }
        problem(request("PUT", "/admin/events/bad", owner, valid, "\"0\""), 400, "INVALID_REQUEST");
        problem(request("DELETE", "/admin/events/" + id, owner, null, "\"0\""), 400, "INVALID_REQUEST");
        problem(request("DELETE", "/admin/events/" + id + "?commandId=bad", owner, null, "\"0\""), 400, "INVALID_REQUEST");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.product_event").query(Long.class).single()).isZero();
    }

    private HttpResponse<String> request(String method, String path, String bearer, String body, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path)).timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        if (version != null) request.header("If-Match", version);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(UUID actor, String scopes, boolean admin) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(actor.toString()).audience("mnema-api")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("generation", "0").claim("scope", scopes).claim("admin", admin).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(new JOSEObjectType("at+jwt")).build(), claims);
        token.sign(new RSASSASigner(KEY));
        return token.serialize();
    }

    private static EventRequests.Command command(UUID commandId, String title, String body, boolean published) {
        return new EventRequests.Command(commandId, title, body, LocalDate.of(2026, 10, 7), published);
    }

    private static String commandJson(boolean published) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("commandId", UUID.randomUUID().toString())
                .put("title", "Заголовок").put("bodyMarkdown", "Текст").put("eventDate", "2026-10-07").put("published", published);
        return body.toString();
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(UUID.fromString(item.path("eventId").stringValue(null))));
        return result;
    }

    private static JsonNode json(HttpResponse<String> response) { return ContractFixtures.JSON.readTree(response.body()); }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
        assertThat(response.body()).doesNotContain("SQL", "SQLException", "stackTrace", "bodyMarkdown");
    }
}
