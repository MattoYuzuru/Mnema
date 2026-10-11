package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/13 (#435) over the real servlet stack and a real PostgreSQL: invitation links, the requests for access and the grants by login, with the shapes of
 * {@code contracts/decks/access.json}. The Identity login lookup is the {@link HttpIdentityFixture}; the time-dependent limits are in
 * {@link AccessServiceIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeckAccessHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode FIXTURE = fixture();

    @LocalServerPort private int port;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    private LibraryFixtures fixtures;

    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        // the tests of this class spend more login lookups in a minute than one instance should: the budgets themselves are tested on the limiter and the service
        registry.add("learning.community.grants.instance-lookups-per-minute", () -> 1_000);
    }

    @BeforeEach
    void fixtures() { fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications); }

    private static JsonNode fixture() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/decks/access.json"))) root = root.getParent();
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/decks/access.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---- helpers ----

    private record Shared(Deck deck, String code) { UUID id() { return deck.id(); } }

    private Shared shared(DeckVisibility level) {
        UUID id = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Колода " + UUID.randomUUID(), "Описание")).acknowledgement()
                .path("deck").path("deckId").stringValue(null));
        Deck deck = new Deck(owner, id, null, null);
        return new Shared(deck, fixtures.publishAt(deck, level));
    }

    private HttpResponse<String> call(String method, String path, UUID account, String body) {
        return HttpIdentityFixture.send(port, method, path, account == null ? null : HttpIdentityFixture.reader(account), body, Map.of());
    }

    private HttpResponse<String> post(String path, UUID account, ObjectNode body) { return call("POST", path, account, body == null ? null : body.toString()); }

    private static ObjectNode spec(Integer maxUses, Integer expiresInDays) {
        ObjectNode body = JSON.createObjectNode();
        if (maxUses == null) body.putNull("maxUses"); else body.put("maxUses", maxUses);
        if (expiresInDays == null) body.putNull("expiresInDays"); else body.put("expiresInDays", expiresInDays);
        return body;
    }

    private static JsonNode json(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    private JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json(response);
    }

    private JsonNode createInvite(Shared deck, Integer maxUses, Integer expiresInDays) {
        return ok(post("/decks/" + deck.id() + "/invites", owner, spec(maxUses, expiresInDays)), 201);
    }

    private HttpResponse<String> redeem(UUID account, String token) { return post("/invites:redeem", account, JSON.createObjectNode().put("token", token)); }

    private HttpResponse<String> ask(UUID account, String code) { return call("POST", "/public/decks/" + code + "/access-requests", account, null); }

    private HttpResponse<String> read(UUID account, String code) { return call("GET", "/public/decks/" + code, account, null); }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    private static void sameShape(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertThat(actual.isObject()).as(path).isTrue();
            assertThat(new TreeSet<>(actual.propertyNames())).as(path).isEqualTo(new TreeSet<>(expected.propertyNames()));
            expected.properties().forEach(entry -> sameShape(entry.getValue(), actual.path(entry.getKey()), path + "." + entry.getKey()));
        } else if (expected.isArray() && !expected.isEmpty() && !actual.isEmpty()) {
            sameShape(expected.get(0), actual.get(0), path + "[]");
        } else if (expected.isString()) {
            assertThat(actual.isString()).as(path).isTrue();
        } else if (expected.isBoolean() || expected.isNumber()) {
            assertThat(actual.getNodeType()).as(path).isEqualTo(expected.getNodeType());
        }
    }

    private List<String> notifications(UUID recipient, String kind) {
        return jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id = :owner AND kind = :kind ORDER BY seq")
                .param("owner", recipient).param("kind", kind).query(String.class).list();
    }

    private String requestStatus(UUID deck, UUID requester) {
        return jdbc.sql("SELECT status FROM app_learning.deck_access_request WHERE deck_id = :deck AND requester_id = :r").param("deck", deck).param("r", requester)
                .query(String.class).optional().orElse(null);
    }

    // ---- invitation links ----

    @Test
    void anInvitationIsReturnedOnceWithItsTokenAndOnlyTheDigestIsStored() throws Exception {
        Shared deck = shared(DeckVisibility.INVITE);
        HttpResponse<String> response = post("/decks/" + deck.id() + "/invites", owner, spec(1, 7));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode created = json(response);
        sameShape(FIXTURE.path("invites").path("create").path("answer").path("body"), created, "create");
        String token = created.path("token").stringValue(null);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(created.path("link").stringValue(null)).isEqualTo("/d/" + deck.code() + "?invite=" + token);
        assertThat(created.path("invite").path("status").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(created.path("invite").path("uses").intValue(-1)).isZero();
        assertThat(created.path("invite").path("maxUses").intValue(-1)).isEqualTo(1);

        UUID invite = UUID.fromString(created.path("invite").path("inviteId").stringValue(null));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(jdbc.sql("SELECT token_hash FROM app_learning.deck_invite WHERE invite_id = :id").param("id", invite).query(byte[].class).single()).isEqualTo(digest);
        String stored = jdbc.sql("SELECT to_jsonb(i)::text FROM app_learning.deck_invite i WHERE invite_id = :id").param("id", invite).query(String.class).single();
        assertThat(stored).doesNotContain(token);

        HttpResponse<String> listed = call("GET", "/decks/" + deck.id() + "/invites", owner, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(listed.body()).doesNotContain(token);
        JsonNode list = json(listed);
        sameShape(FIXTURE.path("invites").path("list").path("answer"), list, "list");
        assertThat(list.path("items")).hasSize(1);
        assertThat(list.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void redeemingGrantsReadAccessOnceAndTheAnswerIsTheCurrentCode() {
        Shared deck = shared(DeckVisibility.INVITE);
        String token = createInvite(deck, 2, 7).path("token").stringValue(null);
        UUID reader = UUID.randomUUID();
        problem(read(reader, deck.code()), 403, "DECK_INVITE_ONLY");
        HttpResponse<String> redeemed = redeem(reader, token);
        assertThat(redeemed.statusCode()).isEqualTo(200);
        assertThat(redeemed.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode body = json(redeemed);
        sameShape(FIXTURE.path("invites").path("redeem").path("answer").path("body"), body, "redeem");
        assertThat(body.path("code").stringValue(null)).isEqualTo(deck.code());
        HttpResponse<String> summary = read(reader, deck.code());
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(json(summary).path("access").stringValue(null)).isEqualTo("GRANTEE");
        assertThat(json(redeem(reader, token)).path("code").stringValue(null)).isEqualTo(deck.code());
        JsonNode invite = json(call("GET", "/decks/" + deck.id() + "/invites", owner, null)).path("items").get(0);
        assertThat(invite.path("uses").intValue(-1)).as("a repeat consumes nothing").isEqualTo(1);
        UUID second = UUID.randomUUID();
        assertThat(redeem(second, token).statusCode()).isEqualTo(200);
        UUID third = UUID.randomUUID();
        problem(redeem(third, token), 404, "RESOURCE_NOT_FOUND");
        assertThat(json(call("GET", "/decks/" + deck.id() + "/invites", owner, null)).path("items").get(0).path("status").stringValue(null)).isEqualTo("EXHAUSTED");
        problem(read(third, deck.code()), 403, "DECK_INVITE_ONLY");
    }

    @Test
    void everyTokenThatOpensNothingIsOneAndTheSameNotFound() {
        Shared deck = shared(DeckVisibility.INVITE);
        String revoked = createInvite(deck, 5, 3).path("token").stringValue(null);
        UUID invite = UUID.fromString(json(call("GET", "/decks/" + deck.id() + "/invites", owner, null)).path("items").get(0).path("inviteId").stringValue(null));
        HttpResponse<String> revocation = post("/decks/" + deck.id() + "/invites/" + invite + ":revoke", owner, null);
        JsonNode revokedShape = ok(revocation, 200);
        sameShape(FIXTURE.path("invites").path("revoke").path("answer"), revokedShape, "revoke");
        assertThat(revokedShape.path("status").stringValue(null)).isEqualTo("REVOKED");
        assertThat(post("/decks/" + deck.id() + "/invites/" + invite + ":revoke", owner, null).statusCode()).as("twice").isEqualTo(200);

        String exhausted = createInvite(deck, 1, null).path("token").stringValue(null);
        assertThat(redeem(UUID.randomUUID(), exhausted).statusCode()).isEqualTo(200);

        UUID caller = UUID.randomUUID();
        List<String> shapes = new ArrayList<>();
        for (String token : new String[] {revoked, exhausted, "A".repeat(43), "not-a-token", "", "x".repeat(500), "q3Jx0u5QmW8bYk2LzTn7VdPaE1sGcH4rFiOe6NhAé"}) {
            HttpResponse<String> response = redeem(caller, token);
            problem(response, 404, "RESOURCE_NOT_FOUND");
            JsonNode problem = json(response);
            shapes.add(problem.path("status") + "|" + problem.path("title") + "|" + problem.path("detail") + "|" + problem.path("instance") + "|" + problem.path("type"));
        }
        assertThat(shapes).as("nothing tells the failures apart").containsOnly(shapes.getFirst());
        problem(read(caller, deck.code()), 403, "DECK_INVITE_ONLY");
    }

    @Test
    void aDowngradeKeepsTheGrantsButEveryOlderInvitationAndTheOldCodeAreDead() {
        Shared deck = shared(DeckVisibility.INVITE);
        String token = createInvite(deck, null, null).path("token").stringValue(null);
        UUID reader = UUID.randomUUID();
        assertThat(redeem(reader, token).statusCode()).isEqualTo(200);
        assertThat(read(reader, deck.code()).statusCode()).isEqualTo(200);
        fixtures.level(deck.deck(), DeckVisibility.PRIVATE);
        problem(read(reader, deck.code()), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id()).query(Long.class).single()).isEqualTo(1);
        problem(redeem(UUID.randomUUID(), token), 404, "RESOURCE_NOT_FOUND");
        problem(post("/decks/" + deck.id() + "/invites", owner, spec(null, null)), 400, "PUBLICATION_REQUIRED");
        String rotated = fixtures.level(deck.deck(), DeckVisibility.INVITE);
        assertThat(rotated).isNotEqualTo(deck.code());
        problem(read(reader, deck.code()), 404, "RESOURCE_NOT_FOUND");
        assertThat(read(reader, rotated).statusCode()).as("the grant survived the downgrade").isEqualTo(200);
        // the invitation of the old generation opens nothing, for a newcomer and for the person who is already in
        problem(redeem(UUID.randomUUID(), token), 404, "RESOURCE_NOT_FOUND");
        problem(redeem(reader, token), 404, "RESOURCE_NOT_FOUND");
        JsonNode listed = json(call("GET", "/decks/" + deck.id() + "/invites", owner, null)).path("items").get(0);
        assertThat(listed.path("status").stringValue(null)).isEqualTo("STALE");
        // a new invitation belongs to the new generation and leads to the new code
        JsonNode fresh = createInvite(deck, null, null);
        assertThat(fresh.path("link").stringValue(null)).startsWith("/d/" + rotated + "?invite=");
        UUID late = UUID.randomUUID();
        assertThat(json(redeem(late, fresh.path("token").stringValue(null))).path("code").stringValue(null)).isEqualTo(rotated);
        assertThat(read(late, rotated).statusCode()).isEqualTo(200);
    }

    @Test
    void aLinkOrPublicDeckHasNobodyToLetInAndARevokedPersonStaysOutWhileTheLinkIsLive() {
        Shared deck = shared(DeckVisibility.INVITE);
        String token = createInvite(deck, null, null).path("token").stringValue(null);
        UUID person = UUID.randomUUID();
        assertThat(redeem(person, token).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/decks/" + deck.id() + "/grants/" + person, owner, null).statusCode()).isEqualTo(204);
        problem(read(person, deck.code()), 403, "DECK_INVITE_ONLY");
        problem(redeem(person, token), 404, "RESOURCE_NOT_FOUND");
        problem(read(person, deck.code()), 403, "DECK_INVITE_ONLY");
        assertThat(ask(person, deck.code()).statusCode()).as("asking is the silent answer").isEqualTo(202);
        assertThat(requestStatus(deck.id(), person)).isEqualTo("REJECTED");
        // the owner may add the person on purpose
        String login = "back." + person.toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(login, person);
        assertThat(post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", login)).statusCode()).isEqualTo(200);
        assertThat(read(person, deck.code()).statusCode()).isEqualTo(200);
        // raising the level: a valid invitation is a no-op answer with the code
        fixtures.level(deck.deck(), DeckVisibility.LINK);
        UUID visitor = UUID.randomUUID();
        assertThat(json(redeem(visitor, token)).path("code").stringValue(null)).isEqualTo(deck.code());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE grantee_id = :id").param("id", visitor).query(Long.class).single()).isZero();
        assertThat(json(call("GET", "/decks/" + deck.id() + "/invites", owner, null)).path("items").get(0).path("uses").intValue(-1)).isEqualTo(1);
    }

    @Test
    void aRequesterTakesTheRequestBackWithAnIdempotentUniformAnswer() {
        Shared deck = shared(DeckVisibility.INVITE);
        UUID asker = UUID.randomUUID();
        ask(asker, deck.code());
        HttpResponse<String> withdrawn = call("POST", "/public/decks/" + deck.code() + "/access-requests:withdraw", asker, null);
        assertThat(withdrawn.statusCode()).isEqualTo(204);
        assertThat(withdrawn.body()).isEmpty();
        assertThat(requestStatus(deck.id(), asker)).isEqualTo("WITHDRAWN");
        assertThat(call("POST", "/public/decks/" + deck.code() + "/access-requests:withdraw", asker, null).statusCode()).as("twice").isEqualTo(204);
        assertThat(call("POST", "/public/decks/" + deck.code() + "/access-requests:withdraw", stranger, null).statusCode()).as("nothing open").isEqualTo(204);
        assertThat(requestStatus(deck.id(), stranger)).isNull();
        assertThat(ok(call("GET", "/decks/" + deck.id() + "/access-requests", owner, null), 200).path("items")).isEmpty();
        problem(call("POST", "/public/decks/" + "1".repeat(10) + "/access-requests:withdraw", asker, null), 404, "RESOURCE_NOT_FOUND");
        assertThat(HttpIdentityFixture.send(port, "POST", "/public/decks/" + deck.code() + "/access-requests:withdraw", null, null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(HttpIdentityFixture.send(port, "POST", "/public/decks/" + deck.code() + "/access-requests:withdraw", HttpIdentityFixture.token(asker, "learning.read"), null,
                Map.of()).statusCode()).isEqualTo(403);
        assertThat(ask(asker, deck.code()).statusCode()).as("asked again at once").isEqualTo(202);
        assertThat(requestStatus(deck.id(), asker)).isEqualTo("PENDING");
    }

    @Test
    void aGuestIsRefusedAndAReadOnlyTokenCannotWrite() {
        Shared deck = shared(DeckVisibility.INVITE);
        String token = createInvite(deck, null, null).path("token").stringValue(null);
        UUID reader = UUID.randomUUID();
        for (String bearer : new String[] {null}) {
            assertThat(HttpIdentityFixture.send(port, "POST", "/invites:redeem", bearer, "{\"token\":\"" + token + "\"}", Map.of()).statusCode()).isEqualTo(401);
            assertThat(HttpIdentityFixture.send(port, "POST", "/public/decks/" + deck.code() + "/access-requests", bearer, null, Map.of()).statusCode()).isEqualTo(401);
        }
        String readOnly = HttpIdentityFixture.token(reader, "learning.read");
        assertThat(HttpIdentityFixture.send(port, "POST", "/invites:redeem", readOnly, "{\"token\":\"" + token + "\"}", Map.of()).statusCode()).isEqualTo(403);
        assertThat(HttpIdentityFixture.send(port, "POST", "/public/decks/" + deck.code() + "/access-requests", readOnly, null, Map.of()).statusCode()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id()).query(Long.class).single()).isZero();
        // a revoked or unknown account is 401, never a silent guest
        UUID revoked = UUID.randomUUID();
        HttpIdentityFixture.revoke(revoked);
        assertThat(ask(revoked, deck.code()).statusCode()).isEqualTo(401);
        // the public chain still offers nothing else
        assertThat(call("PUT", "/public/decks/" + deck.code() + "/access-requests", reader, null).statusCode()).isIn(401, 403, 405);
        assertThat(call("GET", "/public/decks/" + deck.code() + "/access-requests:withdraw", reader, null).statusCode()).isIn(404, 405);
        assertThat(call("DELETE", "/public/decks/" + deck.code() + "/access-requests:withdraw", reader, null).statusCode()).isIn(401, 403, 405);
        assertThat(call("DELETE", "/public/decks/" + deck.code() + "/access-requests", reader, null).statusCode()).isIn(401, 403, 405);
        assertThat(call("POST", "/public/decks/" + deck.code(), reader, null).statusCode()).isIn(401, 403, 405);
    }

    @Test
    void theBodiesAreStrict() {
        Shared deck = shared(DeckVisibility.INVITE);
        for (String body : new String[] {"{}", "{\"maxUses\":1}", "{\"maxUses\":0,\"expiresInDays\":7}", "{\"maxUses\":1001,\"expiresInDays\":null}",
                "{\"maxUses\":1,\"expiresInDays\":366}", "{\"maxUses\":1,\"expiresInDays\":7,\"x\":1}", "{\"maxUses\":\"1\",\"expiresInDays\":7}", "[]", "{"}) {
            problem(call("POST", "/decks/" + deck.id() + "/invites", owner, body), 400, "INVALID_REQUEST");
        }
        for (String body : new String[] {"{}", "{\"token\":5}", "{\"token\":\"a\",\"x\":1}", "{\"tokens\":\"a\"}"}) {
            problem(call("POST", "/invites:redeem", stranger, body), 400, "INVALID_REQUEST");
        }
        for (String body : new String[] {"{}", "{\"username\":null}", "{\"username\":\"a\",\"x\":1}"}) {
            problem(call("POST", "/decks/" + deck.id() + "/grants", owner, body), 400, "INVALID_REQUEST");
        }
        for (String query : new String[] {"?limit=0", "?limit=101", "?limit=x", "?cursor=zzz", "?limit=1&limit=2"}) {
            for (String route : new String[] {"/invites", "/access-requests", "/grants"}) {
                problem(call("GET", "/decks/" + deck.id() + route + query, owner, null), 400, "INVALID_REQUEST");
            }
        }
        assertThat(call("POST", "/decks/not-a-uuid/invites", owner, "{\"maxUses\":1,\"expiresInDays\":1}").statusCode()).isEqualTo(400);
        problem(post("/decks/" + deck.id() + "/invites/not-a-uuid:revoke", owner, null), 400, "INVALID_REQUEST");
    }

    @Test
    void noLogLineCarriesAnInvitationToken() {
        Shared deck = shared(DeckVisibility.INVITE);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        String token;
        try {
            token = createInvite(deck, 1, 1).path("token").stringValue(null);
            redeem(UUID.randomUUID(), token);
            redeem(UUID.randomUUID(), token);
            call("POST", "/invites:redeem", stranger, "{\"token\":\"" + token + "\",\"bad\":1}");
            call("POST", "/invites:redeem", stranger, "{\"token\":\"" + token);
            call("POST", "/invites:redeem", stranger, "{\"token\":\"" + token + "\"}x");
            HttpIdentityFixture.send(port, "POST", "/invites:redeem", null, "{\"token\":\"" + token + "\"}", Map.of());
            HttpIdentityFixture.send(port, "POST", "/invites:redeem", "garbage", "{\"token\":\"" + token + "\"}", Map.of());
        } finally {
            root.detachAppender(appender);
        }
        String digest = HexFormat.of().formatHex(sha256(token));
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContain(token).doesNotContain(digest);
            assertThat(String.valueOf(event.getMDCPropertyMap())).doesNotContain(token);
            if (event.getThrowableProxy() != null) assertThat(String.valueOf(event.getThrowableProxy().getMessage())).doesNotContain(token);
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---- requests for access ----

    @Test
    void aRequestIsAcceptedOnceTheAuthorIsToldAndTheApprovalLetsTheRequesterIn() {
        Shared deck = shared(DeckVisibility.INVITE);
        UUID asker = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        problem(read(asker, deck.code()), 403, "DECK_INVITE_ONLY");
        HttpResponse<String> sent = ask(asker, deck.code());
        assertThat(sent.statusCode()).isEqualTo(202);
        assertThat(sent.headers().firstValue("cache-control")).contains("private, no-store");
        sameShape(FIXTURE.path("requests").path("send").path("answer").path("body"), json(sent), "send");
        assertThat(json(sent).path("status").stringValue(null)).isEqualTo("PENDING");
        assertThat(ask(asker, deck.code()).statusCode()).as("a repeat").isEqualTo(202);
        assertThat(ask(second, deck.code()).statusCode()).isEqualTo(202);
        List<String> told = notifications(owner, "DECK_ACCESS_REQUESTED");
        assertThat(told).as("two askers, one day, one notification").hasSize(1);
        assertThat(told.getFirst()).contains(deck.id().toString()).doesNotContain(asker.toString()).doesNotContain(second.toString());

        JsonNode open = ok(call("GET", "/decks/" + deck.id() + "/access-requests", owner, null), 200);
        sameShape(FIXTURE.path("requests").path("list").path("answer"), open, "requests");
        assertThat(open.path("items")).extracting(item -> item.path("requesterId").stringValue(null)).containsExactly(second.toString(), asker.toString());

        JsonNode approved = ok(post("/decks/" + deck.id() + "/access-requests/" + asker + ":approve", owner, null), 200);
        sameShape(FIXTURE.path("requests").path("approve").path("answer"), approved, "approve");
        assertThat(approved.path("status").stringValue(null)).isEqualTo("APPROVED");
        assertThat(read(asker, deck.code()).statusCode()).isEqualTo(200);
        assertThat(notifications(asker, "DECK_ACCESS_GRANTED")).singleElement().asString().contains(deck.code()).doesNotContain(owner.toString());
        assertThat(ok(post("/decks/" + deck.id() + "/access-requests/" + asker + ":approve", owner, null), 200)).isEqualTo(approved);
        assertThat(notifications(asker, "DECK_ACCESS_GRANTED")).hasSize(1);
        problem(post("/decks/" + deck.id() + "/access-requests/" + asker + ":reject", owner, null), 404, "RESOURCE_NOT_FOUND");

        JsonNode rejected = ok(post("/decks/" + deck.id() + "/access-requests/" + second + ":reject", owner, null), 200);
        sameShape(FIXTURE.path("requests").path("reject").path("answer"), rejected, "reject");
        assertThat(rejected.path("status").stringValue(null)).isEqualTo("REJECTED");
        assertThat(notifications(second, "DECK_ACCESS_GRANTED")).isEmpty();
        problem(read(second, deck.code()), 403, "DECK_INVITE_ONLY");
        assertThat(ask(second, deck.code()).statusCode()).as("asking again is the same answer").isEqualTo(202);
        assertThat(requestStatus(deck.id(), second)).isEqualTo("REJECTED");
        assertThat(ok(call("GET", "/decks/" + deck.id() + "/access-requests", owner, null), 200).path("items")).isEmpty();
        problem(post("/decks/" + deck.id() + "/access-requests/" + UUID.randomUUID() + ":approve", owner, null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void thereIsNothingToAskForExceptOnAnInvitationDeckThatTheCallerCannotRead() {
        Shared invitation = shared(DeckVisibility.INVITE);
        Shared link = shared(DeckVisibility.LINK);
        Shared priv = shared(DeckVisibility.INVITE);
        fixtures.level(priv.deck(), DeckVisibility.PRIVATE);
        problem(ask(owner, invitation.code()), 400, "INVALID_REQUEST");
        problem(ask(stranger, link.code()), 400, "INVALID_REQUEST");
        HttpResponse<String> unknown = ask(stranger, "1".repeat(10));
        HttpResponse<String> hidden = ask(stranger, priv.code());
        problem(unknown, 404, "RESOURCE_NOT_FOUND");
        problem(hidden, 404, "RESOURCE_NOT_FOUND");
        assertThat(json(hidden).path("detail")).isEqualTo(json(unknown).path("detail"));
        problem(ask(stranger, "short"), 404, "RESOURCE_NOT_FOUND");
        UUID grantee = UUID.randomUUID();
        publications.grant(owner, invitation.id(), grantee, GrantRole.VIEWER);
        problem(ask(grantee, invitation.code()), 400, "INVALID_REQUEST");
        // the author turns requests off: the 403 of the invitation screen, nothing written, nobody told
        jdbc.sql("UPDATE app_learning.deck_publication SET requests_enabled = FALSE, row_version = row_version + 1 WHERE deck_id = :deck").param("deck", invitation.id()).update();
        HttpResponse<String> off = ask(stranger, invitation.code());
        problem(off, 403, "DECK_INVITE_ONLY");
        assertThat(off.body()).doesNotContain(invitation.id().toString()).doesNotContain("Колода");
        assertThat(requestStatus(invitation.id(), stranger)).isNull();
        assertThat(notifications(owner, "DECK_ACCESS_REQUESTED")).isEmpty();
        assertThat(FIXTURE.path("problems").path("requestsOff").path("status").intValue()).isEqualTo(403);
    }

    @Test
    void aRequesterIsRefusedTheSixthRequestOfADayWithRetryAfter() {
        UUID asker = UUID.randomUUID();
        for (int index = 0; index < 5; index++) assertThat(ask(asker, shared(DeckVisibility.INVITE).code()).statusCode()).isEqualTo(202);
        HttpResponse<String> sixth = ask(asker, shared(DeckVisibility.INVITE).code());
        problem(sixth, 429, "RATE_LIMITED");
        assertThat(Long.parseLong(sixth.headers().firstValue("retry-after").orElseThrow())).isBetween(86_000L, 86_400L);
        assertThat(json(sixth).path("retryAfter").longValue(0)).isBetween(86_000L, 86_400L);
    }

    @Test
    void revokingAGrantClosesAccessAtOnce() {
        Shared deck = shared(DeckVisibility.INVITE);
        UUID asker = UUID.randomUUID();
        ask(asker, deck.code());
        post("/decks/" + deck.id() + "/access-requests/" + asker + ":approve", owner, null);
        assertThat(read(asker, deck.code()).statusCode()).isEqualTo(200);
        JsonNode grants = ok(call("GET", "/decks/" + deck.id() + "/grants", owner, null), 200);
        sameShape(FIXTURE.path("grants").path("list").path("answer"), grants, "grants");
        assertThat(grants.path("items")).extracting(item -> item.path("granteeId").stringValue(null)).containsExactly(asker.toString());
        HttpResponse<String> revoked = call("DELETE", "/decks/" + deck.id() + "/grants/" + asker, owner, null);
        assertThat(revoked.statusCode()).isEqualTo(204);
        assertThat(revoked.body()).isEmpty();
        problem(read(asker, deck.code()), 403, "DECK_INVITE_ONLY");
        assertThat(call("DELETE", "/decks/" + deck.id() + "/grants/" + asker, owner, null).statusCode()).as("twice").isEqualTo(204);
        assertThat(call("DELETE", "/decks/" + deck.id() + "/grants/" + UUID.randomUUID(), owner, null).statusCode()).isEqualTo(204);
        assertThat(requestStatus(deck.id(), asker)).isEqualTo("REJECTED");
        assertThat(ok(call("GET", "/decks/" + deck.id() + "/grants", owner, null), 200).path("items")).isEmpty();
        assertThat(call("DELETE", "/decks/" + deck.id() + "/grants/" + asker, stranger, null).statusCode()).as("a stranger").isEqualTo(404);
    }

    // ---- grants by login ----

    @Test
    void grantingByLoginResolvesOnlyPublicProfilesAndIsIdempotent() {
        Shared deck = shared(DeckVisibility.INVITE);
        UUID anna = UUID.randomUUID();
        String login = "anna." + anna.toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(login, anna);
        HttpResponse<String> granted = post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", login.toUpperCase(java.util.Locale.ROOT)));
        assertThat(granted.statusCode()).isEqualTo(200);
        assertThat(granted.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode grant = json(granted);
        sameShape(FIXTURE.path("grants").path("add").path("answer"), grant, "grant");
        assertThat(grant.path("granteeId").stringValue(null)).isEqualTo(anna.toString());
        assertThat(grant.path("role").stringValue(null)).isEqualTo("VIEWER");
        assertThat(read(anna, deck.code()).statusCode()).isEqualTo(200);
        assertThat(json(post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", login)))).as("idempotent").isEqualTo(grant);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id()).query(Long.class).single()).isEqualTo(1);

        // consent withdrawn or never given, unknown, malformed: one answer
        UUID hidden = UUID.randomUUID();
        String hiddenLogin = "hidden." + hidden.toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(hiddenLogin, hidden);
        HttpIdentityFixture.hideLogin(hiddenLogin);
        List<String> answers = new ArrayList<>();
        for (String username : new String[] {hiddenLogin, "nobody.here", "ab", "has space", "x".repeat(60), "semi;colon", "../etc"}) {
            HttpResponse<String> response = post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", username));
            problem(response, 404, "RESOURCE_NOT_FOUND");
            JsonNode problem = json(response);
            answers.add(problem.path("status") + "|" + problem.path("detail") + "|" + problem.path("instance") + "|" + problem.path("type"));
        }
        assertThat(answers).containsOnly(answers.getFirst());
        problem(read(hidden, deck.code()), 403, "DECK_INVITE_ONLY");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aStrangerNeverMakesLearningAskIdentityAndTheOwnersOwnLoginIsRefused() {
        Shared deck = shared(DeckVisibility.INVITE);
        String ownLogin = "owner." + owner.toString().substring(0, 8);
        HttpIdentityFixture.publicLogin(ownLogin, owner);
        int before = HttpIdentityFixture.profileLookups();
        problem(post("/decks/" + deck.id() + "/grants", stranger, JSON.createObjectNode().put("username", ownLogin)), 404, "RESOURCE_NOT_FOUND");
        assertThat(HttpIdentityFixture.profileLookups()).as("no lookup for a deck that is not yours").isEqualTo(before);
        problem(post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", ownLogin)), 400, "INVALID_REQUEST");
        assertThat(HttpIdentityFixture.profileLookups()).isEqualTo(before + 1);
        // a malformed login is decided locally
        problem(post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", "no spaces")), 404, "RESOURCE_NOT_FOUND");
        assertThat(HttpIdentityFixture.profileLookups()).isEqualTo(before + 1);
        // a grant means nothing unless the deck is a published invitation deck: no lookup for a LINK or private one
        Shared link = shared(DeckVisibility.LINK);
        problem(post("/decks/" + link.id() + "/grants", owner, JSON.createObjectNode().put("username", ownLogin)), 400, "PUBLICATION_REQUIRED");
        fixtures.level(deck.deck(), DeckVisibility.PRIVATE);
        problem(post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", ownLogin)), 400, "PUBLICATION_REQUIRED");
        assertThat(HttpIdentityFixture.profileLookups()).isEqualTo(before + 1);
    }

    @Test
    void identityBeingLimitedOrDownGrantsNothingAndTwentyLookupsAMinuteIsTheOwnersBudget() {
        Shared deck = shared(DeckVisibility.INVITE);
        HttpResponse<String> limited = post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", HttpIdentityFixture.LIMITED_LOGIN));
        problem(limited, 429, "RATE_LIMITED");
        assertThat(limited.headers().firstValue("retry-after")).contains("7");
        problem(post("/decks/" + deck.id() + "/grants", owner, JSON.createObjectNode().put("username", HttpIdentityFixture.BROKEN_LOGIN)), 503, "IDENTITY_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id()).query(Long.class).single()).isZero();
        // the owner has used two of twenty lookups this minute; a fresh owner proves the budget
        UUID spender = UUID.randomUUID();
        Deck own = new Deck(spender, UUID.fromString(decks.create(spender, new DeckCommand(UUID.randomUUID(), "Моя", "Описание")).acknowledgement().path("deck")
                .path("deckId").stringValue(null)), null, null);
        fixtures.publishAt(own, DeckVisibility.INVITE);
        int ok = 0;
        HttpResponse<String> last = null;
        for (int index = 0; index < LoginLookupLimiter.DEFAULT_PER_OWNER + 1; index++) {
            last = post("/decks/" + own.id() + "/grants", spender, JSON.createObjectNode().put("username", "nobody." + index));
            if (last.statusCode() == 404) ok++;
        }
        assertThat(ok).isEqualTo(LoginLookupLimiter.DEFAULT_PER_OWNER);
        problem(last, 429, "RATE_LIMITED");
        assertThat(Long.parseLong(last.headers().firstValue("retry-after").orElseThrow())).isBetween(1L, 60L);
    }

    @Test
    void ownerRoutesAreOwnerOnlyAndAGranteeIsNoOwner() {
        Shared deck = shared(DeckVisibility.INVITE);
        String token = createInvite(deck, null, null).path("token").stringValue(null);
        UUID reader = UUID.randomUUID();
        redeem(reader, token);
        UUID invite = UUID.fromString(json(call("GET", "/decks/" + deck.id() + "/invites", owner, null)).path("items").get(0).path("inviteId").stringValue(null));
        for (UUID who : new UUID[] {reader, stranger}) {
            problem(post("/decks/" + deck.id() + "/invites", who, spec(null, null)), 404, "RESOURCE_NOT_FOUND");
            problem(call("GET", "/decks/" + deck.id() + "/invites", who, null), 404, "RESOURCE_NOT_FOUND");
            problem(post("/decks/" + deck.id() + "/invites/" + invite + ":revoke", who, null), 404, "RESOURCE_NOT_FOUND");
            problem(call("GET", "/decks/" + deck.id() + "/access-requests", who, null), 404, "RESOURCE_NOT_FOUND");
            problem(post("/decks/" + deck.id() + "/access-requests/" + reader + ":approve", who, null), 404, "RESOURCE_NOT_FOUND");
            problem(post("/decks/" + deck.id() + "/access-requests/" + reader + ":reject", who, null), 404, "RESOURCE_NOT_FOUND");
            problem(call("GET", "/decks/" + deck.id() + "/grants", who, null), 404, "RESOURCE_NOT_FOUND");
            problem(post("/decks/" + deck.id() + "/grants", who, JSON.createObjectNode().put("username", "someone")), 404, "RESOURCE_NOT_FOUND");
            problem(call("DELETE", "/decks/" + deck.id() + "/grants/" + reader, who, null), 404, "RESOURCE_NOT_FOUND");
        }
        assertThat(read(reader, deck.code()).statusCode()).as("nothing was revoked").isEqualTo(200);
        assertThat(json(call("GET", "/decks/" + deck.id() + "/invites", owner, null)).path("items").get(0).path("status").stringValue(null)).isEqualTo("ACTIVE");
        // the same answer as for a deck that does not exist
        HttpResponse<String> absent = call("GET", "/decks/" + UUID.randomUUID() + "/grants", owner, null);
        problem(absent, 404, "RESOURCE_NOT_FOUND");
        assertThat(new LinkedHashMap<>(Map.of("a", json(absent).path("detail").stringValue(null)))).isEqualTo(new LinkedHashMap<>(Map.of("a",
                json(call("GET", "/decks/" + deck.id() + "/grants", stranger, null)).path("detail").stringValue(null))));
    }
}
