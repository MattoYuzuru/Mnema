package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.ContractFixtures;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.image;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/8 (#430) on the real servlet stack and a real PostgreSQL: explicit publication, the metadata, the «Публичная» checklist (the public-profile claim
 * arrives through the Identity {@code /userinfo} fixture), the outbox, the topic directory, and the shapes of {@code contracts/decks/publication.json}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicationHttpIntegrationTest extends PostgresIntegrationTest {
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
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private LibraryFixtures fixtures;

    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void fixtures() {
        fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        HttpIdentityFixture.publicProfile(owner, true);
    }

    private static JsonNode fixture() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/decks/publication.json"))) root = root.getParent();
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/decks/publication.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---- helpers ----

    private HttpResponse<String> call(String method, String path, UUID account, String body, String ifMatch) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (ifMatch != null) headers.put("If-Match", ifMatch);
        return HttpIdentityFixture.send(port, method, path, account == null ? null : HttpIdentityFixture.reader(account), body, headers);
    }

    private HttpResponse<String> read(UUID deck) { return call("GET", "/decks/" + deck + "/publication", owner, null, null); }

    private HttpResponse<String> put(UUID deck, String ifMatch, ObjectNode body) { return call("PUT", "/decks/" + deck + "/publication", owner, body.toString(), ifMatch); }

    private static JsonNode json(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    private JsonNode state(UUID deck) {
        HttpResponse<String> response = read(deck);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static String q(String version) { return "\"" + version + "\""; }

    private static ObjectNode metadata(String topic, String content, String target, String level, String... tags) {
        ObjectNode result = JSON.createObjectNode();
        result.put("topicId", topic).put("contentLanguage", content).put("targetLanguage", target).put("level", level);
        ArrayNode array = result.putArray("tags");
        for (String tag : tags) array.add(tag);
        return result;
    }

    private static ObjectNode full() { return metadata("japanese", "ru", "ja", "A2", "jlpt n5"); }

    private ObjectNode command(String visibility, ObjectNode metadata, boolean requests, UUID expectedHead, String note) {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", visibility);
        body.set("metadata", metadata);
        body.put("requestsEnabled", requests);
        if (expectedHead == null) body.putNull("publish");
        else body.putObject("publish").put("expectedHeadRevisionId", expectedHead.toString()).put("releaseNote", note);
        return body;
    }

    private ObjectNode publishAt(String visibility, UUID deck) {
        return command(visibility, full(), true, head(deck), null);
    }

    private UUID head(UUID deck) { return UUID.fromString(decks.read(owner, deck).path("revisionId").stringValue(null)); }

    private long deckVersion(UUID deck) { return jdbc.sql("SELECT row_version FROM app_learning.deck WHERE deck_id = :deck").param("deck", deck).query(Long.class).single(); }

    private long revisions(UUID deck) { return jdbc.sql("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id = :deck").param("deck", deck).query(Long.class).single(); }

    private long events(UUID deck) { return jdbc.sql("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :deck").param("deck", deck).query(Long.class).single(); }

    private boolean hasRow(UUID deck) { return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.deck_publication WHERE deck_id = :deck)").param("deck", deck).query(Boolean.class).single(); }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    /** Both nodes have the same property names at every level (arrays by their first element); scalar values are not compared. */
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

    private UUID deckWithDescription(String title, String description) {
        UUID id = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), title, description)).acknowledgement().path("deck").path("deckId").stringValue(null));
        return id;
    }

    // ---- first publication ----

    @Test
    void leavingPrivateIsTheFirstPublicationAndCreatesNoRevision() {
        Deck deck = fixtures.deck(owner, "Японский — заметки");
        HttpResponse<String> initial = read(deck.id());
        assertThat(initial.statusCode()).isEqualTo(200);
        assertThat(initial.headers().firstValue("etag")).contains("\"0\"");
        assertThat(initial.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode never = json(initial);
        sameShape(FIXTURE.path("state").path("never").path("body"), never, "never");
        assertThat(never.path("visibility").stringValue(null)).isEqualTo("PRIVATE");
        assertThat(never.path("publicCode").isNull()).isTrue();
        assertThat(never.path("link").isNull()).isTrue();
        assertThat(never.path("unpublishedChanges").isNull()).isTrue();
        assertThat(never.path("rowVersion").stringValue(null)).isEqualTo("0");
        assertThat(never.path("headRevisionId").stringValue(null)).isEqualTo(head(deck.id()).toString());
        assertThat(never.path("checklist").path("catalogThreshold").path("needMaterials").intValue(0)).isEqualTo(10);
        assertThat(never.path("requestsEnabled").booleanValue(false)).isTrue();
        assertThat(hasRow(deck.id())).isFalse();

        long version = deckVersion(deck.id());
        long revisions = revisions(deck.id());
        UUID head = head(deck.id());
        HttpResponse<String> saved = put(deck.id(), "\"0\"", command("LINK", full(), true, head, null));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.headers().firstValue("etag")).contains("\"1\"");
        assertThat(saved.headers().firstValue("idempotency-replayed")).isEmpty();
        assertThat(saved.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode ack = json(saved);
        sameShape(FIXTURE.path("put").path("acknowledgement").path("body"), ack, "ack");
        JsonNode publication = ack.path("publication");
        assertThat(ack.path("changed").booleanValue(false)).isTrue();
        assertThat(publication.path("visibility").stringValue(null)).isEqualTo("LINK");
        String code = publication.path("publicCode").stringValue(null);
        assertThat(code).hasSize(10);
        assertThat(publication.path("link").stringValue(null)).isEqualTo("/d/" + code);
        assertThat(publication.path("publishedRevisionId").stringValue(null)).isEqualTo(head.toString());
        assertThat(publication.path("unpublishedChanges").intValue(-1)).isZero();
        assertThat(publication.path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(publication.path("metadata").path("tags").get(0).stringValue(null)).isEqualTo("jlpt n5");

        // no deck revision, and the deck's own version did not move: an open editor gets no 412
        assertThat(deckVersion(deck.id())).isEqualTo(version);
        assertThat(revisions(deck.id())).isEqualTo(revisions);
        assertThat(events(deck.id())).isEqualTo(1);
        // the public read path now serves the newly published revision
        HttpResponse<String> publicRead = call("GET", "/public/decks/" + code, stranger, null, null);
        assertThat(publicRead.statusCode()).as(publicRead.body()).isEqualTo(200);
        assertThat(json(publicRead).path("title").stringValue(null)).isEqualTo("Японский — заметки");
        // and the owner's own deck JSON reports the real level
        assertThat(json(call("GET", "/decks/" + deck.id(), owner, null, null)).path("visibility").stringValue(null)).isEqualTo("link");
    }

    @Test
    void leavingPrivateWithoutPublishingIsRefusedAndWritesNothing() {
        Deck deck = fixtures.deck(owner, "Колода");
        ObjectNode body = command("LINK", full(), true, null, null);
        problem(put(deck.id(), "\"0\"", body), 400, "PUBLICATION_REQUIRED");
        assertThat(hasRow(deck.id())).isFalse();
        assertThat(events(deck.id())).isZero();
        // nothing was remembered of the failed command: the same commandId with a publishing body succeeds
        ObjectNode retry = command("LINK", full(), true, head(deck.id()), null);
        retry.put("commandId", body.path("commandId").stringValue(null));
        assertThat(put(deck.id(), "\"0\"", retry).statusCode()).isEqualTo(200);
        // publishing at level PRIVATE is no request at all
        Deck other = fixtures.deck(owner, "Другая");
        problem(put(other.id(), "\"0\"", command("PRIVATE", full(), true, head(other.id()), null)), 400, "INVALID_REQUEST");
    }

    @Test
    void anExactRetryReplaysAndAChangedBodyWithTheSameCommandIdConflicts() {
        Deck deck = fixtures.deck(owner, "Колода");
        ObjectNode body = command("LINK", full(), true, head(deck.id()), "Первая версия");
        HttpResponse<String> first = put(deck.id(), "\"0\"", body);
        assertThat(first.statusCode()).isEqualTo(200);
        HttpResponse<String> replay = put(deck.id(), "\"0\"", body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(replay.headers().firstValue("etag")).isEmpty();
        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(events(deck.id())).isEqualTo(1);
        // the binding includes the whole body and the expected version
        ObjectNode changed = body.deepCopy();
        changed.put("requestsEnabled", false);
        problem(put(deck.id(), "\"0\"", changed), 409, "IDEMPOTENCY_CONFLICT");
        problem(put(deck.id(), "\"1\"", body), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(state(deck.id()).path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(state(deck.id()).path("releaseNote").stringValue(null)).isEqualTo("Первая версия");
        // a replay is answered after the current authorization: a stranger gets the opaque 404, not the receipt
        problem(call("PUT", "/decks/" + deck.id() + "/publication", stranger, body.toString(), "\"0\""), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void aStaleHeadAndAStaleVersionAreRefusedAndNothingChanges() {
        Deck deck = fixtures.deck(owner, "Колода");
        UUID seen = head(deck.id());
        // the editor saves after the owner looked: the head moved
        HttpResponse<String> patched = call("PATCH", "/decks/" + deck.id(), owner,
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"metadata\":{\"title\":\"Новое имя\",\"description\":\"d\"}}", q(Long.toString(deckVersion(deck.id()))));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        problem(put(deck.id(), "\"0\"", command("LINK", full(), true, seen, null)), 412, "VERSION_CONFLICT");
        assertThat(hasRow(deck.id())).isFalse();
        assertThat(events(deck.id())).isZero();

        assertThat(put(deck.id(), "\"0\"", command("LINK", full(), true, head(deck.id()), null)).statusCode()).isEqualTo(200);
        // a stale or missing or malformed If-Match
        ObjectNode again = command("INVITE", full(), true, null, null);
        problem(put(deck.id(), "\"0\"", again), 412, "VERSION_CONFLICT");
        problem(put(deck.id(), null, again), 428, "PRECONDITION_REQUIRED");
        problem(put(deck.id(), "W/\"1\"", again), 400, "INVALID_REQUEST");
        problem(put(deck.id(), "1", again), 400, "INVALID_REQUEST");
        assertThat(state(deck.id()).path("visibility").stringValue(null)).isEqualTo("LINK");
    }

    @Test
    void anOpenEditorStillSavesAfterPublishing() {
        Deck deck = fixtures.deck(owner, "Колода");
        HttpResponse<String> opened = call("GET", "/decks/" + deck.id(), owner, null, null);
        String editorVersion = opened.headers().firstValue("etag").orElseThrow();
        long before = deckVersion(deck.id());
        assertThat(put(deck.id(), "\"0\"", command("LINK", full(), true, head(deck.id()), null)).statusCode()).isEqualTo(200);
        assertThat(put(deck.id(), "\"1\"", command("INVITE", full(), true, null, null)).statusCode()).isEqualTo(200);
        assertThat(deckVersion(deck.id())).isEqualTo(before);
        HttpResponse<String> saved = call("PATCH", "/decks/" + deck.id(), owner,
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"metadata\":{\"title\":\"Правка\",\"description\":\"d\"}}", editorVersion);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(deckVersion(deck.id())).isEqualTo(before + 1);
        // the title edit is a new head but not a changed material or exercise
        JsonNode after = state(deck.id());
        assertThat(after.path("headRevisionId").stringValue(null)).isNotEqualTo(after.path("publishedRevisionId").stringValue(null));
        assertThat(after.path("unpublishedChanges").intValue(-1)).isZero();
        // readers still see the published title
        String code = after.path("publicCode").stringValue(null);
        assertThat(json(call("GET", "/public/decks/" + code, owner, null, null)).path("title").stringValue(null)).isEqualTo("Колода");
        // the editor's concurrent save also wins against a publication in flight (the share lock is brief and nothing is compared)
        assertThat(put(deck.id(), "\"2\"", command("INVITE", full(), true, head(deck.id()), "Правка")).statusCode()).isEqualTo(200);
        assertThat(json(call("GET", "/public/decks/" + code, owner, null, null)).path("title").stringValue(null)).isEqualTo("Правка");
    }

    // ---- counting ----

    @Test
    void unpublishedChangesCountDistinctMaterialsAndExercises() {
        Deck deck = fixtures.deck(owner, "Колода");
        assertThat(put(deck.id(), "\"0\"", command("LINK", full(), true, head(deck.id()), null)).statusCode()).isEqualTo(200);
        assertThat(state(deck.id()).path("unpublishedChanges").intValue(-1)).isZero();
        StudyFixtures.Material first = fixtures.study().addMaterial(owner, deck.id(), "alpha", "beta");
        fixtures.study().addMaterial(owner, deck.id(), "gamma", "delta");
        fixtures.study().publish(first, fixtures.study().freeResponse(first, blocks(text("Q?")), blocks(text("A")), "a"), "Цель");
        JsonNode changed = state(deck.id());
        // two new materials plus the one new exercise
        assertThat(changed.path("unpublishedChanges").intValue(-1)).isEqualTo(3);
        long events = events(deck.id());
        JsonNode republished = json(put(deck.id(), q(changed.path("rowVersion").stringValue(null)), command("LINK", full(), true, head(deck.id()), "Новые материалы")));
        assertThat(republished.path("publication").path("unpublishedChanges").intValue(-1)).isZero();
        assertThat(republished.path("publication").path("releaseNote").stringValue(null)).isEqualTo("Новые материалы");
        assertThat(republished.path("publication").path("rowVersion").stringValue(null)).isEqualTo("2");
        assertThat(events(deck.id())).isEqualTo(events + 1);
    }

    // ---- the checklist ----

    @Test
    void thePublicChecklistFailsOneItemAtATime() {
        // the description of the revision readers get
        UUID blank = deckWithDescription("Без описания", "");
        UUID head = head(blank);
        ObjectNode body = command("PUBLIC", full(), true, head, null);
        problem(put(blank, "\"0\"", body), 409, "PUBLICATION_REQUIREMENTS");
        assertThat(failed(put(blank, "\"0\"", body))).containsExactly("description");
        assertThat(state(blank).path("checklist").path("description").booleanValue(true)).isFalse();

        Deck deck = fixtures.deck(owner, "Колода");
        UUID h = head(deck.id());
        // topic
        assertThat(failed(put(deck.id(), "\"0\"", command("PUBLIC", metadata(null, "ru", null, null), true, h, null)))).containsExactly("topic");
        // language
        assertThat(failed(put(deck.id(), "\"0\"", command("PUBLIC", metadata("japanese", null, null, null), true, h, null)))).containsExactly("language");
        // consent via the userinfo claim
        HttpIdentityFixture.publicProfile(owner, false);
        assertThat(failed(put(deck.id(), "\"0\"", command("PUBLIC", full(), true, h, null)))).containsExactly("publicProfile");
        assertThat(state(deck.id()).path("checklist").path("publicProfile").booleanValue(true)).isFalse();
        // everything at once, in checklist order
        assertThat(failed(put(deck.id(), "\"0\"", command("PUBLIC", metadata(null, null, null, null), true, h, null))))
                .containsExactly("topic", "language", "publicProfile");
        // a lower level needs none of it
        assertThat(put(deck.id(), "\"0\"", command("LINK", metadata(null, null, null, null), true, h, null)).statusCode()).isEqualTo(200);
        assertThat(events(deck.id())).isEqualTo(1);
        // the failures wrote nothing: still the LINK row at wire version 1
        assertThat(state(deck.id()).path("rowVersion").stringValue(null)).isEqualTo("1");
        problem(put(deck.id(), "\"1\"", command("PUBLIC", metadata(null, "ru", null, null), true, null, null)), 409, "PUBLICATION_REQUIREMENTS");
        assertThat(state(deck.id()).path("visibility").stringValue(null)).isEqualTo("LINK");

        // all met: a deck far below the catalog threshold is PUBLIC all the same (visible by link and in the profile)
        HttpIdentityFixture.publicProfile(owner, true);
        JsonNode ok = json(put(deck.id(), "\"1\"", command("PUBLIC", full(), true, null, null)));
        assertThat(ok.path("publication").path("visibility").stringValue(null)).isEqualTo("PUBLIC");
        assertThat(ok.path("publication").path("checklist").path("catalogThreshold").path("met").booleanValue(true)).isFalse();
        String code = ok.path("publication").path("publicCode").stringValue(null);
        assertThat(ok.path("publication").path("link").stringValue(null)).isEqualTo("/d/" + code + "/koloda");
        assertThat(call("GET", "/public/decks/" + code, null, null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void aPublicDeckWhoseConsentWasWithdrawnCannotBeRepublishedButCanBeLowered() {
        Deck deck = fixtures.deck(owner, "Колода");
        assertThat(put(deck.id(), "\"0\"", publishAt("PUBLIC", deck.id())).statusCode()).isEqualTo(200);
        HttpIdentityFixture.publicProfile(owner, false);
        fixtures.study().addMaterial(owner, deck.id(), "new", "words");
        problem(put(deck.id(), "\"1\"", publishAt("PUBLIC", deck.id())), 409, "PUBLICATION_REQUIREMENTS");
        // an unchanged state stays a no-op even without the consent
        ObjectNode same = command("PUBLIC", full(), true, null, null);
        JsonNode noop = json(put(deck.id(), "\"1\"", same));
        assertThat(noop.path("changed").booleanValue(true)).isFalse();
        // a benign edit of the public deck (tags, request switch) exposes nothing new and is not frozen by the withdrawn consent, even with an unpublished head
        long events = events(deck.id());
        JsonNode benign = json(put(deck.id(), "\"1\"", command("PUBLIC", metadata("japanese", "ru", "ja", "A2", "новый тег"), false, null, null)));
        assertThat(benign.path("changed").booleanValue(false)).isTrue();
        assertThat(benign.path("publication").path("visibility").stringValue(null)).isEqualTo("PUBLIC");
        assertThat(events(deck.id())).isEqualTo(events + 1);
        // but the topic and language of a public deck are still required
        assertThat(failed(put(deck.id(), "\"2\"", command("PUBLIC", metadata(null, "ru", null, null), false, null, null)))).containsExactly("topic");
        assertThat(put(deck.id(), "\"2\"", command("LINK", full(), true, head(deck.id()), "Снято с каталога")).statusCode()).isEqualTo(200);
    }

    @Test
    void everyEffectiveWriteOfAPublicDeckAppendsAnOutboxRowAndANoOpAppendsNone() {
        Deck deck = fixtures.deck(owner, "Колода");
        assertThat(put(deck.id(), "\"0\"", publishAt("PUBLIC", deck.id())).statusCode()).isEqualTo(200);
        assertThat(events(deck.id())).isEqualTo(1);
        assertThat(put(deck.id(), "\"1\"", command("PUBLIC", metadata("japanese", "ru", "ja", "A2", "один"), true, null, null)).statusCode()).isEqualTo(200);
        assertThat(put(deck.id(), "\"2\"", command("PUBLIC", metadata("japanese", "ru", "ja", "A2", "один"), false, null, null)).statusCode()).isEqualTo(200);
        assertThat(put(deck.id(), "\"3\"", command("PUBLIC", metadata("english", "ru", "en", "B1", "один"), false, null, null)).statusCode()).isEqualTo(200);
        assertThat(events(deck.id())).isEqualTo(4);
        JsonNode noop = json(put(deck.id(), "\"4\"", command("PUBLIC", metadata("english", "ru", "en", "B1", "один"), false, null, null)));
        assertThat(noop.path("changed").booleanValue(true)).isFalse();
        assertThat(events(deck.id())).isEqualTo(4);
        assertThat(jdbc.sql("SELECT string_agg(visibility, ',') FROM app_learning.deck_publication_event WHERE deck_id = :deck").param("deck", deck.id()).query(String.class).single())
                .isEqualTo("PUBLIC,PUBLIC,PUBLIC,PUBLIC");
    }

    @Test
    void becomingPublicNeedsThePublishedRevisionToBeTheHeadBecauseTheMediaCheckJudgesTheHead() {
        Deck deck = fixtures.deck(owner, "Иллюстрации");
        UUID asset = fixtures.study().readyAsset(owner, "image/png");
        provenance(owner, asset, "CC BY-NC 4.0");
        StudyFixtures.Material host = fixtures.study().addMaterial(owner, deck.id(), "nc", "exercise");
        JsonNode exercise = fixtures.study().publish(host, fixtures.study().selfCheck(host, blocks(image(asset, "nc"), text("Q")), blocks(text("A"))), "Запрещённая");
        // R1 (with the non-commercial image) is shared by link, which is allowed
        assertThat(put(deck.id(), "\"0\"", publishAt("LINK", deck.id())).statusCode()).isEqualTo(200);
        // the owner removes the exercise: the head R2 is clean, the published revision R1 is not
        HttpResponse<String> removed = call("DELETE", "/decks/" + deck.id() + "/exercises/" + exercise.path("exerciseId").stringValue(null), owner, null,
                q(Long.toString(deckVersion(deck.id()))));
        assertThat(removed.statusCode()).as(removed.body()).isBetween(200, 204);
        assertThat(state(deck.id()).path("checklist").path("blockedMedia")).isEmpty();
        assertThat(state(deck.id()).path("headRevisionId").stringValue(null)).isNotEqualTo(state(deck.id()).path("publishedRevisionId").stringValue(null));
        // without publishing, readers would get R1: refused, nothing written
        HttpResponse<String> refused = put(deck.id(), "\"1\"", command("PUBLIC", full(), true, null, null));
        problem(refused, 400, "PUBLICATION_REQUIRED");
        assertThat(state(deck.id()).path("visibility").stringValue(null)).isEqualTo("LINK");
        assertThat(state(deck.id()).path("rowVersion").stringValue(null)).isEqualTo("1");
        // publishing the clean head R2 makes it public
        JsonNode ok = json(put(deck.id(), "\"1\"", command("PUBLIC", full(), true, head(deck.id()), null)));
        assertThat(ok.path("publication").path("visibility").stringValue(null)).isEqualTo("PUBLIC");
        assertThat(ok.path("publication").path("publishedRevisionId").stringValue(null)).isEqualTo(head(deck.id()).toString());
    }

    @Test
    void aPublishThatWaitsForAnEditorSaveAnswersTheStaleHeadNotNotFound() throws Exception {
        Deck deck = fixtures.deck(owner, "Колода");
        UUID seen = head(deck.id());
        long version = deckVersion(deck.id());
        var saving = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
        try (var pool = Executors.newFixedThreadPool(2)) {
            // the editor's save holds the deck row (its new revision is not visible to anybody yet)
            Future<?> editor = pool.submit(() -> template.executeWithoutResult(status -> {
                decks.save(owner, deck.id(), version, new DeckCommand(UUID.randomUUID(), "Новое имя", "d"));
                saving.countDown();
                try { commit.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }));
            saving.await();
            Future<HttpResponse<String>> publish = pool.submit(() -> put(deck.id(), "\"0\"", command("LINK", full(), true, seen, null)));
            Thread.sleep(700);
            assertThat(publish.isDone()).as("the publish waits for the editor's row lock").isFalse();
            commit.countDown();
            editor.get();
            HttpResponse<String> response = publish.get();
            problem(response, 412, "VERSION_CONFLICT");
        }
        assertThat(hasRow(deck.id())).isFalse();
        // the editor won, and a publish of the new head goes through
        assertThat(put(deck.id(), "\"0\"", command("LINK", full(), true, head(deck.id()), null)).statusCode()).isEqualTo(200);
    }

    @Test
    void theNonCommercialMatchIsCaseInsensitive() {
        record Case(String license, boolean blocked) { }
        List<Case> cases = List.of(new Case("CC BY-NC 4.0", true), new Case("cc-by-nc-4.0", true), new Case("CC BY-NC-SA 4.0", true), new Case("NON-COMMERCIAL", true),
                new Case("Non-Commercial use only", true), new Case("noncommercial", true), new Case("Pixabay Content License", false), new Case("CC BY-SA 4.0", false),
                new Case("CC0 1.0", false), new Case("Licence", false), new Case("Public domain", false), new Case("CC BY 4.0", false));
        for (Case value : cases) {
            Deck deck = fixtures.deck(owner, "Лицензия " + value.license());
            UUID asset = fixtures.study().readyAsset(owner, "image/png");
            provenance(owner, asset, value.license());
            StudyFixtures.Material host = fixtures.study().addMaterial(owner, deck.id(), "host", "exercise");
            fixtures.study().publish(host, fixtures.study().selfCheck(host, blocks(image(asset, "x"), text("Q")), blocks(text("A"))), "Цель");
            assertThat(state(deck.id()).path("checklist").path("blockedMedia").size()).as(value.license()).isEqualTo(value.blocked() ? 1 : 0);
        }
    }

    @Test
    void stockMediaUnderANonCommercialLicenseBlocksPublicInTheMaterialsAndExercisesOfTheHead() {
        Deck deck = fixtures.deck(owner, "Иллюстрации");
        UUID blockedAsset = fixtures.study().readyAsset(owner, "image/png");
        UUID freeAsset = fixtures.study().readyAsset(owner, "image/png");
        UUID foreignOwnerAsset = fixtures.study().readyAsset(owner, "image/png");
        provenance(owner, blockedAsset, "CC BY-NC 4.0");
        provenance(owner, freeAsset, "CC BY 4.0");
        // another owner's provenance row naming the same asset id is not evidence about this owner's deck
        provenance(stranger, foreignOwnerAsset, "CC BY-NC-SA 4.0");
        StudyFixtures.Material withFree = fixtures.study().addMaterial(owner, deck.id(), "free", "image");
        fixtures.study().publish(withFree, fixtures.study().selfCheck(withFree, blocks(image(freeAsset, "ok"), text("Q")), blocks(text("A"))), "Свободная");
        StudyFixtures.Material withForeign = fixtures.study().addMaterial(owner, deck.id(), "other", "image");
        fixtures.study().publish(withForeign, fixtures.study().selfCheck(withForeign, blocks(image(foreignOwnerAsset, "ok"), text("Q")), blocks(text("A"))), "Чужая");
        assertThat(state(deck.id()).path("checklist").path("blockedMedia")).isEmpty();
        assertThat(put(deck.id(), "\"0\"", publishAt("PUBLIC", deck.id())).statusCode()).isEqualTo(200);

        // an exercise of the head uses the non-commercial image
        StudyFixtures.Material exerciseHost = fixtures.study().addMaterial(owner, deck.id(), "nc", "exercise");
        JsonNode published = fixtures.study().publish(exerciseHost, fixtures.study().selfCheck(exerciseHost, blocks(image(blockedAsset, "nc"), text("Q")), blocks(text("A"))), "Запрещённая");
        JsonNode blocked = state(deck.id()).path("checklist").path("blockedMedia");
        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).path("exerciseId").stringValue(null)).isEqualTo(published.path("exerciseId").stringValue(null));
        assertThat(blocked.get(0).path("reason").stringValue(null)).isEqualTo("NC_LICENSE");
        // switching a PUBLIC deck's publish to that head is refused; a lower level is not
        problem(put(deck.id(), "\"1\"", publishAt("PUBLIC", deck.id())), 409, "PUBLICATION_REQUIREMENTS");
        assertThat(failed(put(deck.id(), "\"1\"", publishAt("PUBLIC", deck.id())))).containsExactly("blockedMedia");
        assertThat(put(deck.id(), "\"1\"", publishAt("LINK", deck.id())).statusCode()).isEqualTo(200);

        // a material of the head uses it too
        UUID materialAsset = fixtures.study().readyAsset(owner, "image/png");
        provenance(owner, materialAsset, "Creative Commons Non-Commercial");
        UUID member = imageMaterial(deck.id(), materialAsset);
        JsonNode both = state(deck.id()).path("checklist").path("blockedMedia");
        assertThat(both).hasSize(2);
        List<String> keys = new ArrayList<>();
        both.forEach(entry -> keys.add(entry.has("memberKey") ? "memberKey:" + entry.path("memberKey").stringValue(null) : "exerciseId:" + entry.path("exerciseId").stringValue(null)));
        assertThat(keys).contains("memberKey:" + member, "exerciseId:" + published.path("exerciseId").stringValue(null));
    }

    private void provenance(UUID account, UUID asset, String license) {
        ObjectNode entry = JSON.createObjectNode().put("assetId", asset.toString()).put("source", "PIXABAY").put("sourceId", "1").put("license", license)
                .put("sourcePageUrl", "https://example.org/1");
        ArrayNode media = JSON.createArrayNode().add(entry);
        jdbc.sql("""
                INSERT INTO app_learning.generation_provenance(provenance_id, owner_id, session_id, artifact_id, revision_id, published_ref, created_at, media)
                VALUES (:id, :owner, :session, :artifact, :revision, '{}'::jsonb, now(), CAST(:media AS jsonb))
                """).param("id", UUID.randomUUID()).param("owner", account).param("session", UUID.randomUUID()).param("artifact", UUID.randomUUID())
                .param("revision", UUID.randomUUID()).param("media", media.toString()).update();
    }

    /** A material whose document is one image node of the asset (a media reference of the item revision). */
    private UUID imageMaterial(UUID deck, UUID asset) {
        JsonNode head = decks.read(owner, deck);
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        ObjectNode root = document.putObject("root").put("id", UUID.randomUUID().toString()).put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode image = root.putArray("content").addObject().put("id", UUID.randomUUID().toString()).put("type", "image").put("version", 1);
        image.putObject("attrs").put("assetId", asset.toString()).put("alt", "Схема").put("description", "Описание схемы.");
        image.putArray("content");
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        body.set("document", document);
        JsonNode change = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readCreate(ContractFixtures.bytes(body))).acknowledgement().path("changes").get(0);
        return UUID.fromString(change.path("memberKey").stringValue(null));
    }

    private static List<String> failed(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(json(response).path("code").stringValue(null)).isEqualTo("PUBLICATION_REQUIREMENTS");
        List<String> result = new ArrayList<>();
        json(response).path("failed").forEach(key -> result.add(key.stringValue(null)));
        assertThat(response.body()).doesNotContain("Колода");
        return result;
    }

    // ---- levels, codes and the outbox ----

    @Test
    void loweringRotatesTheCodeRaisingKeepsItAndEveryEffectiveChangeAppendsOneEvent() {
        Deck deck = fixtures.deck(owner, "Колода");
        JsonNode created = json(put(deck.id(), "\"0\"", publishAt("PUBLIC", deck.id()))).path("publication");
        String publicCode = created.path("publicCode").stringValue(null);
        assertThat(events(deck.id())).isEqualTo(1);

        JsonNode toLink = json(put(deck.id(), "\"1\"", command("LINK", full(), true, null, null))).path("publication");
        String linkCode = toLink.path("publicCode").stringValue(null);
        assertThat(linkCode).isNotEqualTo(publicCode);
        assertThat(toLink.path("link").stringValue(null)).isEqualTo("/d/" + linkCode);
        assertThat(call("GET", "/public/decks/" + publicCode, stranger, null, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/public/decks/" + linkCode, stranger, null, null).statusCode()).isEqualTo(200);
        assertThat(events(deck.id())).isEqualTo(2);

        JsonNode raised = json(put(deck.id(), "\"2\"", command("PUBLIC", full(), true, null, null))).path("publication");
        assertThat(raised.path("publicCode").stringValue(null)).isEqualTo(linkCode);
        assertThat(raised.path("link").stringValue(null)).startsWith("/d/" + linkCode + "/");
        assertThat(events(deck.id())).isEqualTo(3);

        JsonNode back = json(put(deck.id(), "\"3\"", command("PRIVATE", full(), true, null, null))).path("publication");
        assertThat(back.path("visibility").stringValue(null)).isEqualTo("PRIVATE");
        assertThat(back.path("publicCode").isNull()).isTrue();
        assertThat(back.path("link").isNull()).isTrue();
        assertThat(back.path("publishedRevisionId").isNull()).isFalse();
        assertThat(call("GET", "/public/decks/" + linkCode, stranger, null, null).statusCode()).isEqualTo(404);
        assertThat(events(deck.id())).isEqualTo(4);
        // shared again without publishing: the revision is kept, the code is the one drawn when it went private
        JsonNode again = json(put(deck.id(), "\"4\"", command("INVITE", full(), true, null, null))).path("publication");
        assertThat(again.path("publicCode").stringValue(null)).isNotIn(publicCode, linkCode);
        assertThat(events(deck.id())).isEqualTo(5);
        assertThat(jdbc.sql("SELECT string_agg(visibility, ',' ORDER BY event_id) FROM app_learning.deck_publication_event WHERE deck_id = :deck")
                .param("deck", deck.id()).query(String.class).single()).isEqualTo("PUBLIC,LINK,PUBLIC,PRIVATE,INVITE");
        // the event ids are uuidv7: time-ordered
        assertThat(jdbc.sql("SELECT bool_and(substr(event_id::text, 15, 1) = '7') FROM app_learning.deck_publication_event WHERE deck_id = :deck")
                .param("deck", deck.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void anUnchangedStateWritesNothing() {
        Deck deck = fixtures.deck(owner, "Колода");
        assertThat(put(deck.id(), "\"0\"", publishAt("LINK", deck.id())).statusCode()).isEqualTo(200);
        long events = events(deck.id());
        String publishedAt = state(deck.id()).path("publishedAt").stringValue(null);
        HttpResponse<String> noop = put(deck.id(), "\"1\"", command("LINK", full(), true, head(deck.id()), null));
        assertThat(noop.statusCode()).isEqualTo(200);
        JsonNode ack = json(noop);
        assertThat(ack.path("changed").booleanValue(true)).isFalse();
        assertThat(ack.path("publication").path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(ack.path("publication").path("publishedAt").stringValue(null)).isEqualTo(publishedAt);
        assertThat(noop.headers().firstValue("etag")).contains("\"1\"");
        assertThat(events(deck.id())).isEqualTo(events);
        // a deck that never left PRIVATE with default metadata stays without a row
        Deck untouched = fixtures.deck(owner, "Другая");
        JsonNode nothing = json(put(untouched.id(), "\"0\"", command("PRIVATE", metadata(null, null, null, null), true, null, null)));
        assertThat(nothing.path("changed").booleanValue(true)).isFalse();
        assertThat(nothing.path("publication").path("rowVersion").stringValue(null)).isEqualTo("0");
        assertThat(hasRow(untouched.id())).isFalse();
        // metadata alone on a private deck is kept in a PRIVATE row, without an event
        JsonNode draft = json(put(untouched.id(), "\"0\"", command("PRIVATE", full(), false, null, null)));
        assertThat(draft.path("changed").booleanValue(false)).isTrue();
        assertThat(draft.path("publication").path("visibility").stringValue(null)).isEqualTo("PRIVATE");
        assertThat(draft.path("publication").path("requestsEnabled").booleanValue(true)).isFalse();
        assertThat(draft.path("publication").path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(events(untouched.id())).isZero();
    }

    @Test
    void twoCommandsFromTheSameVersionHaveExactlyOneWinner() throws Exception {
        Deck deck = fixtures.deck(owner, "Колода");
        UUID head = head(deck.id());
        int writers = 6;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(writers)) {
            List<Future<HttpResponse<String>>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                ObjectNode body = command(i % 2 == 0 ? "LINK" : "INVITE", full(), true, head, "n" + i);
                results.add(pool.submit(() -> { start.await(); return put(deck.id(), "\"0\"", body); }));
            }
            start.countDown();
            int ok = 0;
            int stale = 0;
            for (var result : results) {
                int status = result.get().statusCode();
                if (status == 200) ok++;
                else if (status == 412) stale++;
                else throw new AssertionError(status + " " + result.get().body());
            }
            assertThat(ok).isEqualTo(1);
            assertThat(stale).isEqualTo(writers - 1);
        }
        assertThat(state(deck.id()).path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(events(deck.id())).isEqualTo(1);
    }

    // ---- metadata ----

    @Test
    void metadataIsNormalizedAndEveryMalformedShapeIs400() {
        Deck deck = fixtures.deck(owner, "Колода");
        UUID head = head(deck.id());
        JsonNode ok = json(put(deck.id(), "\"0\"", command("LINK", metadata("python", "en", "ru", "BEGINNER", "  JLPT   N5 ", "Кандзи", "c++"), true, head, "  ")));
        assertThat(ok.path("publication").path("metadata").path("tags").toString()).isEqualTo("[\"jlpt n5\",\"кандзи\",\"c++\"]");
        assertThat(ok.path("publication").path("releaseNote").isNull()).isTrue();

        String version = "\"1\"";
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(command("LINK", metadata("no-such-topic", "ru", null, null), true, null, null));
        invalid.add(command("LINK", metadata("languages", "ru", null, null), true, null, null)); // a group, not a leaf
        invalid.add(command("LINK", metadata("Python", "ru", null, null), true, null, null));
        invalid.add(command("LINK", metadata("python", "RU", null, null), true, null, null));
        invalid.add(command("LINK", metadata("python", "russian", null, null), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", "j", null), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, "EXPERT"), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, "a1"), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, null, "a", "b", "c", "d", "e", "f"), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, null, "Python", "python"), true, null, null)); // equal after normalization
        invalid.add(command("LINK", metadata("python", "ru", null, null, ""), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, null, "x".repeat(33)), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, null, "a<b"), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, null, "a\u0000b"), true, null, null));
        invalid.add(command("LINK", metadata("python", "ru", null, null), true, head, "я".repeat(501)));
        invalid.add(command("HIDDEN", metadata("python", "ru", null, null), true, null, null));
        invalid.add(command("link", metadata("python", "ru", null, null), true, null, null));
        ObjectNode notBoolean = command("LINK", metadata("python", "ru", null, null), true, null, null);
        notBoolean.put("requestsEnabled", "yes");
        invalid.add(notBoolean);
        ObjectNode extra = command("LINK", metadata("python", "ru", null, null), true, null, null);
        extra.put("extra", 1);
        invalid.add(extra);
        ObjectNode missing = command("LINK", metadata("python", "ru", null, null), true, null, null);
        missing.remove("requestsEnabled");
        invalid.add(missing);
        ObjectNode noPublish = command("LINK", metadata("python", "ru", null, null), true, null, null);
        noPublish.remove("publish");
        invalid.add(noPublish);
        ObjectNode shortMetadata = command("LINK", metadata("python", "ru", null, null), true, null, null);
        ((ObjectNode) shortMetadata.path("metadata")).remove("level");
        invalid.add(shortMetadata);
        ObjectNode extraMetadata = command("LINK", metadata("python", "ru", null, null), true, null, null);
        ((ObjectNode) extraMetadata.path("metadata")).put("color", "red");
        invalid.add(extraMetadata);
        ObjectNode badCommand = command("LINK", metadata("python", "ru", null, null), true, null, null);
        badCommand.put("commandId", "not-a-uuid");
        invalid.add(badCommand);
        ObjectNode v1 = command("LINK", metadata("python", "ru", null, null), true, null, null);
        v1.put("commandId", "6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        invalid.add(v1);
        ObjectNode badHead = command("LINK", metadata("python", "ru", null, null), true, head, null);
        ((ObjectNode) badHead.path("publish")).put("expectedHeadRevisionId", "nope");
        invalid.add(badHead);
        for (ObjectNode body : invalid) {
            HttpResponse<String> response = put(deck.id(), version, body);
            assertThat(response.statusCode()).as(body.toString().substring(0, Math.min(200, body.toString().length())) + " -> " + response.body()).isEqualTo(400);
            assertThat(json(response).path("code").stringValue(null)).isEqualTo("INVALID_REQUEST");
        }
        for (String text : new String[] {"", "[]", "{", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"commandId\":\"" + UUID.randomUUID() + "\"}", "null"}) {
            HttpResponse<String> response = call("PUT", "/decks/" + deck.id() + "/publication", owner, text, version);
            assertThat(response.statusCode()).as(text).isEqualTo(400);
        }
        assertThat(state(deck.id()).path("rowVersion").stringValue(null)).isEqualTo("1");
        // the body bound of 8192 bytes
        ObjectNode large = command("LINK", metadata("python", "ru", null, null), true, null, null);
        large.put("padding", "x".repeat(9000));
        assertThat(put(deck.id(), version, large).statusCode()).isEqualTo(400);
    }

    @Test
    void aReleaseNoteOfFiveHundredCodePointsIsKept() {
        Deck deck = fixtures.deck(owner, "Колода");
        String note = "ё😀".repeat(250);
        assertThat(note.codePointCount(0, note.length())).isEqualTo(500);
        JsonNode ack = json(put(deck.id(), "\"0\"", command("LINK", full(), true, head(deck.id()), note)));
        assertThat(ack.path("publication").path("releaseNote").stringValue(null)).isEqualTo(note);
        assertThat(state(deck.id()).path("releaseNote").stringValue(null)).isEqualTo(note);
    }

    // ---- ownership, scopes, suggestions, directory ----

    @Test
    void aForeignAbsentOrDeletedDeckIsTheSameOpaque404AndScopesAreEnforced() {
        Deck deck = fixtures.deck(owner, "Чужая колода");
        UUID absent = UUID.randomUUID();
        assertThat(put(deck.id(), "\"0\"", publishAt("LINK", deck.id())).statusCode()).isEqualTo(200);
        for (UUID target : new UUID[] {deck.id(), absent}) {
            HttpResponse<String> get = call("GET", "/decks/" + target + "/publication", stranger, null, null);
            problem(get, 404, "RESOURCE_NOT_FOUND");
            assertThat(get.body()).doesNotContain("Чужая");
            problem(call("PUT", "/decks/" + target + "/publication", stranger, publishAt("PRIVATE", deck.id()).put("publish", (String) null).toString(), "\"0\""), 404, "RESOURCE_NOT_FOUND");
        }
        // a read-only token cannot write, no token is 401
        HttpResponse<String> readOnly = HttpIdentityFixture.send(port, "PUT", "/decks/" + deck.id() + "/publication", HttpIdentityFixture.token(owner, "learning.read"),
                publishAt("LINK", deck.id()).toString(), Map.of("If-Match", "\"1\""));
        assertThat(readOnly.statusCode()).isEqualTo(403);
        assertThat(call("GET", "/decks/" + deck.id() + "/publication", null, null, null).statusCode()).isEqualTo(401);
        // a deleted deck is gone for its owner too
        decks.delete(owner, deck.id(), deckVersion(deck.id()));
        problem(read(deck.id()), 404, "RESOURCE_NOT_FOUND");
        // malformed identifiers are 400
        assertThat(call("GET", "/decks/not-a-uuid/publication", owner, null, null).statusCode()).isEqualTo(400);
    }

    @Test
    void theStateSuggestsALanguageAndTopicsAndStoresNeitherOfThem() {
        UUID deck = deckWithDescription("Японский язык: кандзи для начинающих", "Слова и иероглифы для изучения японского языка на каждый день.");
        JsonNode suggested = state(deck).path("suggested");
        assertThat(suggested.path("contentLanguage").stringValue(null)).isEqualTo("ru");
        List<String> topics = new ArrayList<>();
        suggested.path("topicIds").forEach(topic -> topics.add(topic.stringValue(null)));
        assertThat(topics).startsWith("japanese").hasSizeLessThanOrEqualTo(3);
        assertThat(state(deck).path("metadata").path("topicId").isNull()).isTrue();
        assertThat(hasRow(deck)).isFalse();

        UUID english = deckWithDescription("Python and SQL cheat sheet", "How to write queries and scripts with the tools you have.");
        JsonNode other = state(english).path("suggested");
        assertThat(other.path("contentLanguage").stringValue(null)).isEqualTo("en");
        List<String> englishTopics = new ArrayList<>();
        other.path("topicIds").forEach(topic -> englishTopics.add(topic.stringValue(null)));
        assertThat(englishTopics).contains("python", "sql-data");

        UUID unsure = deckWithDescription("Колода", "");
        assertThat(state(unsure).path("suggested").path("topicIds")).isEmpty();
    }

    @Test
    void theTopicDirectoryIsAuthenticatedCacheableStaticData() {
        assertThat(call("GET", "/topics", null, null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> response = call("GET", "/topics", owner, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("private, max-age=3600");
        JsonNode tree = json(response);
        sameShape(FIXTURE.path("topics").path("body"), tree, "topics");
        JsonNode tops = tree.path("topics");
        assertThat(tops.size()).isGreaterThanOrEqualTo(10);
        List<Integer> ordinals = new ArrayList<>();
        tops.forEach(top -> ordinals.add(top.path("ordinal").intValue(0)));
        assertThat(ordinals).isSorted();
        JsonNode languages = tops.get(0);
        assertThat(languages.path("topicId").stringValue(null)).isEqualTo("languages");
        assertThat(languages.path("nameRu").stringValue(null)).isEqualTo("Языки");
        List<String> children = new ArrayList<>();
        languages.path("children").forEach(child -> children.add(child.path("topicId").stringValue(null)));
        assertThat(children).contains("english", "japanese", "other-languages");
        JsonNode other = null;
        for (JsonNode top : tops) if ("other".equals(top.path("topicId").stringValue(null))) other = top;
        assertThat(other).isNotNull();
        assertThat(other.path("children")).isEmpty();
        // every leaf (and only a leaf) is selectable
        assertThat(put(fixtures.deck(owner, "К").id(), "\"0\"", command("PRIVATE", metadata("other", "ru", null, null), true, null, null)).statusCode()).isEqualTo(200);
    }

    @Test
    void theOwnersDeckJsonReportsTheRealLevelInDetailListAndAcknowledgement() {
        Deck deck = fixtures.deck(owner, "Колода");
        Deck untouched = fixtures.deck(owner, "Нетронутая");
        assertThat(json(call("GET", "/decks/" + untouched.id(), owner, null, null)).path("visibility").stringValue(null)).isEqualTo("private");
        for (String level : new String[] {"LINK", "INVITE", "PUBLIC", "PRIVATE"}) {
            String version = state(deck.id()).path("rowVersion").stringValue(null);
            ObjectNode body = command(level, full(), true, "LINK".equals(level) || "PUBLIC".equals(level) ? head(deck.id()) : null, null);
            assertThat(put(deck.id(), q(version), body).statusCode()).isEqualTo(200);
            String lower = level.toLowerCase(java.util.Locale.ROOT);
            assertThat(json(call("GET", "/decks/" + deck.id(), owner, null, null)).path("visibility").stringValue(null)).isEqualTo(lower);
            JsonNode page = json(call("GET", "/decks?limit=10", owner, null, null)).path("items");
            String listed = null;
            for (JsonNode item : page) if (deck.id().toString().equals(item.path("deckId").stringValue(null))) listed = item.path("visibility").stringValue(null);
            assertThat(listed).isEqualTo(lower);
            // an editor's metadata save keeps the level in its acknowledgement
            HttpResponse<String> saved = call("PATCH", "/decks/" + deck.id(), owner,
                    "{\"commandId\":\"" + UUID.randomUUID() + "\",\"metadata\":{\"title\":\"Колода\",\"description\":\"d" + level + "\"}}", q(Long.toString(deckVersion(deck.id()))));
            assertThat(json(saved).path("deck").path("visibility").stringValue(null)).isEqualTo(lower);
        }
    }

    @Test
    void thePublishedRevisionIsTheOneReadersGetNotTheHead() {
        Deck deck = fixtures.deck(owner, "Колода");
        JsonNode ack = json(put(deck.id(), "\"0\"", publishAt("PUBLIC", deck.id())));
        String code = ack.path("publication").path("publicCode").stringValue(null);
        fixtures.study().addMaterial(owner, deck.id(), "later", "edit");
        JsonNode items = json(call("GET", "/public/decks/" + code + "/items", stranger, null, null));
        assertThat(items.path("items").size()).isEqualTo(1);
        assertThat(json(call("GET", "/decks/" + deck.id() + "/items", owner, null, null)).path("items").size()).isEqualTo(2);
        assertThat(put(deck.id(), "\"1\"", command("PUBLIC", full(), true, head(deck.id()), "Ещё материал")).statusCode()).isEqualTo(200);
        assertThat(json(call("GET", "/public/decks/" + code + "/items", stranger, null, null)).path("items").size()).isEqualTo(2);
    }
}
