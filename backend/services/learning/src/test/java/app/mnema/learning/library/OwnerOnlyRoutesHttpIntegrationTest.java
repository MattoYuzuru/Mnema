package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CD-3: sharing a deck changes who may READ its published revision and nothing else. A grantee of an invitation deck and an ordinary account on a LINK
 * or PUBLIC deck call every existing deck, item, exercise, study, generation, media-manifest, capture and draft route with the REAL ids of the owner's
 * deck, and each answers the same opaque 404 as for a deck that does not exist, writes nothing and leaks nothing. The table is checked for completeness
 * against the route inventory of the running application, so a new deck route cannot ship without a row (and so without this proof).
 *
 * <p>Every row is first sent by the owner to an identical deck of his own (the request is well-formed and reaches the domain: the owner's answer is not
 * a 404 unless the row names a resource that does not exist in the fixture), then by the stranger to the shared deck.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnerOnlyRoutesHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    /** The owner's fixture world: every id a row may need. */
    private record World(UUID owner, Deck deck, long version, UUID deckRevision, UUID exerciseRevision, StudyFixtures.Issued issued, UUID note,
                         UUID draft, UUID asset, JsonNode document) { }

    /** One request of a row; {@code ifMatch} is the quoted deck version when the route needs one. */
    private record Request(String method, String path, String body, String ifMatch) { }

    private record Row(String route, Function<World, Request> request, int ownerMayGet) {
        static Row of(String route, Function<World, Request> request) { return new Row(route, request, 0); }

        /** A route addressed by an id the fixture cannot create (a generation session, a variant): the owner's 404 is expected too. */
        static Row absent(String route, Function<World, Request> request) { return new Row(route, request, 404); }

        /** A well-formed request that the domain itself refuses for the owner (no transcript to reveal, a spec the capability gate rejects): 400. */
        static Row refused(String route, Function<World, Request> request) { return new Row(route, request, 400); }
    }

    private static String q(long version) { return "\"" + version + "\""; }

    private static Request get(String path) { return new Request("GET", path, null, null); }

    private static Request send(String method, String path, ObjectNode body, String ifMatch) {
        return new Request(method, path, body == null ? null : body.toString(), ifMatch);
    }

    private static ObjectNode metadata() {
        ObjectNode body = command();
        body.putObject("metadata").put("title", "Захват").put("description", "x");
        return body;
    }

    /** A well-formed publication command: share by link and publish the head the fixture saw. */
    private static ObjectNode publication(World w) {
        ObjectNode body = command().put("visibility", "LINK").put("requestsEnabled", true);
        body.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").putNull("targetLanguage").putNull("level").putArray("tags");
        body.putObject("publish").put("expectedHeadRevisionId", w.deckRevision().toString()).putNull("releaseNote");
        return body;
    }

    private static ObjectNode command() { return JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()); }

    private List<Row> rows() {
        UUID random = UUID.randomUUID();
        List<Row> rows = new ArrayList<>();
        // decks
        rows.add(Row.of("GET /decks/{deckId}", w -> get("/decks/" + w.deck().id())));
        rows.add(Row.of("PATCH /decks/{deckId}", w -> send("PATCH", "/decks/" + w.deck().id(), metadata(), q(w.version()))));
        rows.add(Row.of("DELETE /decks/{deckId}", w -> send("DELETE", "/decks/" + w.deck().id(), null, q(w.version()))));
        rows.add(Row.of("GET /decks/{deckId}/insights", w -> get("/decks/" + w.deck().id() + "/insights")));
        // publication (Share/8)
        rows.add(Row.of("GET /decks/{deckId}/publication", w -> get("/decks/" + w.deck().id() + "/publication")));
        rows.add(Row.of("PUT /decks/{deckId}/publication", w -> send("PUT", "/decks/" + w.deck().id() + "/publication", publication(w), q(w.version()))));
        // materials
        rows.add(Row.of("GET /decks/{deckId}/items", w -> get("/decks/" + w.deck().id() + "/items")));
        rows.add(Row.of("GET /decks/{deckId}/items/{memberKey}", w -> get("/decks/" + w.deck().id() + "/items/" + w.deck().material().member())));
        rows.add(Row.of("POST /decks/{deckId}/items", w -> {
            ObjectNode body = command().put("expectedDeckRevisionId", w.deckRevision().toString());
            body.set("document", w.document().deepCopy());
            return send("POST", "/decks/" + w.deck().id() + "/items", body, q(w.version()));
        }));
        rows.add(Row.of("PUT /decks/{deckId}/items/{memberKey}", w -> {
            ObjectNode body = command().put("expectedDeckRevisionId", w.deckRevision().toString())
                    .put("expectedItemRevisionId", w.deck().material().itemRevision().toString()).put("expectedOrdinal", 0);
            body.set("document", w.document().deepCopy());
            return send("PUT", "/decks/" + w.deck().id() + "/items/" + w.deck().material().member(), body, q(w.version()));
        }));
        rows.add(Row.of("POST /decks/{deckId}/items/publications", w -> {
            ObjectNode body = command().put("expectedDeckRevisionId", w.deckRevision().toString());
            body.putArray("changes").addObject().put("operation", "create").put("memberKey", UUID.randomUUID().toString())
                    .set("document", w.document().deepCopy());
            return send("POST", "/decks/" + w.deck().id() + "/items/publications", body, q(w.version()));
        }));
        rows.add(Row.of("POST /decks/{deckId}/items/{memberKey}/exemplar", w -> send("POST",
                "/decks/" + w.deck().id() + "/items/" + w.deck().material().member() + "/exemplar",
                command().put("expectedItemRevisionId", w.deck().material().itemRevision().toString()).put("exemplar", true), null)));
        rows.add(Row.of("POST /decks/{deckId}/items/deletions/preview", w -> {
            ObjectNode body = JSON.createObjectNode().put("expectedDeckRevisionId", w.deckRevision().toString());
            body.putArray("itemIds").add(w.deck().material().member().toString());
            return send("POST", "/decks/" + w.deck().id() + "/items/deletions/preview", body, null);
        }));
        rows.add(Row.of("POST /decks/{deckId}/items/deletions", w -> {
            ObjectNode body = command().put("expectedDeckRevisionId", w.deckRevision().toString());
            body.putArray("itemIds").add(w.deck().material().member().toString());
            return send("POST", "/decks/" + w.deck().id() + "/items/deletions", body, q(w.version()));
        }));
        // exercises
        rows.add(Row.of("GET /decks/{deckId}/exercises", w -> get("/decks/" + w.deck().id() + "/exercises")));
        rows.add(Row.of("GET /decks/{deckId}/exercises/{exerciseId}", w -> get("/decks/" + w.deck().id() + "/exercises/" + w.deck().exercise())));
        rows.add(Row.of("POST /decks/{deckId}/exercises", w -> send("POST", "/decks/" + w.deck().id() + "/exercises", newExercise(w), q(w.version()))));
        rows.add(Row.of("PUT /decks/{deckId}/exercises/{exerciseId}", w -> send("PUT", "/decks/" + w.deck().id() + "/exercises/" + w.deck().exercise(),
                newExercise(w).put("expectedExerciseRevisionId", w.exerciseRevision().toString()), q(w.version()))));
        rows.add(Row.of("DELETE /decks/{deckId}/exercises/{exerciseId}/new-mark", w -> send("DELETE",
                "/decks/" + w.deck().id() + "/exercises/" + w.deck().exercise() + "/new-mark", null, null)));
        rows.add(Row.of("DELETE /decks/{deckId}/exercises/{exerciseId}", w -> send("DELETE", "/decks/" + w.deck().id() + "/exercises/" + w.deck().exercise(), null, q(w.version()))));
        // Study
        rows.add(Row.of("POST /decks/{deckId}/study-sessions", w -> send("POST", "/decks/" + w.deck().id() + "/study-sessions", startBody(), null)));
        rows.add(Row.of("GET /decks/{deckId}/study-sessions/{sessionId}", w -> get("/decks/" + w.deck().id() + "/study-sessions/" + w.issued().session())));
        rows.add(Row.of("GET /decks/{deckId}/study-sessions/replay-sources", w -> get("/decks/" + w.deck().id() + "/study-sessions/replay-sources")));
        rows.add(Row.of("POST /decks/{deckId}/study-sessions/{sessionId}/presentations", w -> send("POST",
                "/decks/" + w.deck().id() + "/study-sessions/" + w.issued().session() + "/presentations", null, null)));
        rows.add(Row.refused("POST /decks/{deckId}/study-sessions/{sessionId}/presentations/{presentationId}/transcript", w -> send("POST",
                presentation(w) + "/transcript", JSON.createObjectNode().put("nonce", w.issued().nonce()), null)));
        rows.add(Row.refused("POST /decks/{deckId}/study-sessions/{sessionId}/presentations/{presentationId}/hints", w -> send("POST",
                presentation(w) + "/hints", JSON.createObjectNode().put("nonce", w.issued().nonce()).put("blankId", UUID.randomUUID().toString()), null)));
        rows.add(Row.of("POST /decks/{deckId}/study-sessions/{sessionId}/attempts", w -> send("POST",
                session(w) + "/attempts", attempt(w, UUID.randomUUID()), null)));
        rows.add(Row.absent("GET /decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}", w -> get(session(w) + "/attempts/" + random)));
        rows.add(Row.absent("POST /decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}/self-check", w -> send("POST",
                session(w) + "/attempts/" + random + "/self-check", null, null)));
        rows.add(Row.absent("POST /decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}/self-rating", w -> send("POST",
                session(w) + "/attempts/" + random + "/self-rating", JSON.createObjectNode().put("rating", "FULL"), null)));
        rows.add(Row.absent("POST /decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}/dispute", w -> send("POST",
                session(w) + "/attempts/" + random + "/dispute", command(), null)));
        rows.add(Row.of("POST /decks/{deckId}/study-sessions/{sessionId}/pair-checks", w -> send("POST", session(w) + "/pair-checks",
                JSON.createObjectNode().put("presentationId", w.issued().id().toString()).put("nonce", w.issued().nonce())
                        .put("leftId", UUID.randomUUID().toString()).put("rightId", UUID.randomUUID().toString()), null)));
        rows.add(Row.of("POST /decks/{deckId}/study-restarts", w -> {
            ObjectNode body = command();
            body.putArray("memberKeys").add(w.deck().material().member().toString());
            return send("POST", "/decks/" + w.deck().id() + "/study-restarts", body, null);
        }));
        rows.add(Row.of("GET /decks/{deckId}/study-progress", w -> get("/decks/" + w.deck().id() + "/study-progress")));
        // generation
        String sessionBase = "/generation-sessions/";
        rows.add(Row.refused("POST /decks/{deckId}/generation-intents", w -> send("POST", "/decks/" + w.deck().id() + "/generation-intents",
                JSON.createObjectNode().put("text", "сделай карточки"), null)));
        rows.add(Row.refused("POST /decks/{deckId}/generation-estimates", w -> send("POST", "/decks/" + w.deck().id() + "/generation-estimates",
                JSON.createObjectNode().put("spec", "x"), null)));
        rows.add(Row.refused("POST /decks/{deckId}/generation-sessions", w -> {
            ObjectNode body = command();
            body.putObject("spec").put("kind", "MATERIALS");
            return send("POST", "/decks/" + w.deck().id() + "/generation-sessions", body, null);
        }));
        rows.add(Row.of("GET /decks/{deckId}/generation-sessions", w -> get("/decks/" + w.deck().id() + "/generation-sessions")));
        for (String suffix : List.of("", "/events")) {
            rows.add(Row.absent("GET /decks/{deckId}/generation-sessions/{sessionId}" + suffix, w -> get("/decks/" + w.deck().id() + sessionBase + random + suffix)));
        }
        for (String suffix : List.of("/plan-approval", "/cancellation", "/approvals", "/note-archival")) {
            rows.add(Row.absent("POST /decks/{deckId}/generation-sessions/{sessionId}" + suffix, w -> send("POST",
                    "/decks/" + w.deck().id() + sessionBase + random + suffix, command(), "\"0\"")));
        }
        rows.add(Row.absent("DELETE /decks/{deckId}/generation-sessions/{sessionId}", w -> send("DELETE", "/decks/" + w.deck().id() + sessionBase + random, null, "\"0\"")));
        String artifact = "/decks/{deckId}/generation-sessions/{sessionId}/artifacts/{artifactId}";
        rows.add(Row.absent("GET " + artifact, w -> get("/decks/" + w.deck().id() + sessionBase + random + "/artifacts/" + random)));
        for (String suffix : List.of("/approval", "/rejection", "/handoff", "/retry", "/edits", "/revert", "/media-slots/{slotKey}/selection")) {
            rows.add(Row.absent("POST " + artifact + suffix, w -> send("POST", "/decks/" + w.deck().id() + sessionBase + random + "/artifacts/" + random
                    + suffix.replace("{slotKey}", "slot"), command(), "\"0\"")));
        }
        rows.add(Row.absent("DELETE " + artifact + "/rejection", w -> send("DELETE", "/decks/" + w.deck().id() + sessionBase + random + "/artifacts/" + random + "/rejection", null, null)));
        // media manifests of the deck
        rows.add(Row.of("GET /decks/{deckId}/media-manifests/current", w -> get("/decks/" + w.deck().id() + "/media-manifests/current")));
        rows.add(Row.absent("GET /decks/{deckId}/media-manifests/{manifestId}", w -> get("/decks/" + w.deck().id() + "/media-manifests/" + random)));
        // drafts and capture notes of the deck (addressed by their own ids, or naming the deck in the body)
        rows.add(Row.of("POST /capture-notes", w -> send("POST", "/capture-notes", command().put("deckId", w.deck().id().toString()).put("source", "s").put("text", "t"), null)));
        rows.add(Row.of("GET /capture-notes?deckId", w -> get("/capture-notes?deckId=" + w.deck().id())));
        rows.add(Row.of("GET /capture-notes/{noteId}", w -> get("/capture-notes/" + w.note())));
        rows.add(Row.of("PUT /capture-notes/{noteId}", w -> send("PUT", "/capture-notes/" + w.note(), JSON.createObjectNode().put("source", "s").put("text", "changed"), "\"0\"")));
        rows.add(Row.of("POST /capture-notes/{noteId}/archive", w -> send("POST", "/capture-notes/" + w.note() + "/archive", JSON.createObjectNode().put("archived", true), "\"0\"")));
        rows.add(Row.of("POST /capture-notes/{noteId}/conversions", w -> {
            ObjectNode body = command().put("expectedDeckVersion", Long.toString(w.version())).put("expectedDeckRevisionId", w.deckRevision().toString());
            body.set("document", w.document().deepCopy());
            return send("POST", "/capture-notes/" + w.note() + "/conversions", body, "\"0\"");
        }));
        rows.add(Row.of("DELETE /capture-notes/{noteId}", w -> send("DELETE", "/capture-notes/" + w.note(), null, "\"0\"")));
        rows.add(Row.of("POST /editing-drafts", w -> {
            ObjectNode body = command().put("deckId", w.deck().id().toString());
            body.set("document", w.document().deepCopy());
            return send("POST", "/editing-drafts", body, null);
        }));
        rows.add(Row.of("GET /editing-drafts/{draftId}", w -> get("/editing-drafts/" + w.draft())));
        rows.add(Row.of("PUT /editing-drafts/{draftId}", w -> {
            ObjectNode body = command();
            body.set("document", w.document().deepCopy());
            return send("PUT", "/editing-drafts/" + w.draft(), body, "\"0\"");
        }));
        rows.add(Row.of("DELETE /editing-drafts/{draftId}", w -> send("DELETE", "/editing-drafts/" + w.draft(), null, "\"0\"")));
        // media of the owner (not addressed by the deck, but it is the deck's pictures and sound)
        rows.add(Row.absent("GET /media-assets/{assetId}/upload", w -> get("/media-assets/" + w.asset() + "/upload")));
        rows.add(Row.of("GET /media-assets/{assetId}/playback", w -> get("/media-assets/" + w.asset() + "/playback")));
        rows.add(Row.absent("GET /media-assets/{assetId}/variants/{variantId}/download", w -> get("/media-assets/" + w.asset() + "/variants/" + random + "/download")));
        // destructive rows last, so that every earlier row of the owner's run still finds what it names
        List<String> last = List.of("DELETE /decks/{deckId}/exercises/{exerciseId}", "POST /decks/{deckId}/items/deletions", "DELETE /capture-notes/{noteId}",
                "DELETE /editing-drafts/{draftId}", "DELETE /decks/{deckId}");
        rows.sort(java.util.Comparator.comparingInt(row -> last.indexOf(row.route())));
        return rows;
    }

    private static String session(World w) { return "/decks/" + w.deck().id() + "/study-sessions/" + w.issued().session(); }

    private static String presentation(World w) { return session(w) + "/presentations/" + w.issued().id(); }

    private static ObjectNode startBody() {
        ObjectNode body = command().put("mode", "SCHEDULED");
        body.putObject("budget").put("maxPresentations", 5).put("maxNewObjectives", 2);
        return body;
    }

    private static ObjectNode attempt(World w, UUID attemptId) {
        ObjectNode body = JSON.createObjectNode().put("attemptId", attemptId.toString()).put("presentationId", w.issued().id().toString())
                .put("nonce", w.issued().nonce());
        body.putObject("response").put("kind", "TEXT").put("text", "memory");
        body.putNull("confidence");
        return body.put("durationMs", 1_000);
    }

    private ObjectNode newExercise(World w) {
        var study = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        ObjectNode body = command().put("expectedDeckRevisionId", w.deckRevision().toString());
        body.putObject("objective").put("operation", "create").put("title", "Чужая цель");
        body.set("exercise", study.selfCheck(w.deck().material(), StudyFixtures.blocks(StudyFixtures.text("Вопрос")),
                StudyFixtures.blocks(StudyFixtures.text("Ответ"))));
        return body;
    }

    private World world(DeckVisibility level, UUID grantee) {
        LibraryFixtures fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        UUID owner = UUID.randomUUID();
        Deck deck = fixtures.deck(owner, "Колода " + level);
        if (grantee != null) publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        fixtures.publishAt(deck, level);
        JsonNode head = decks.read(owner, deck.id());
        JsonNode exercise = exercises.list(owner, deck.id(), null, null).path("exercises").get(0);
        JsonNode document = new SharedScopeFixture(decks, items, storage, jdbc, transactions).document("чужой текст");
        StudyFixtures.Issued issued = fixtures.study().issueOne(deck.material());
        UUID asset = fixtures.study().readyAsset(owner, "image/png");
        String token = HttpIdentityFixture.reader(owner);
        ObjectNode note = command().put("deckId", deck.id().toString()).put("source", "источник").put("text", "заметка");
        JsonNode noteAck = json(HttpIdentityFixture.send(port, "POST", "/capture-notes", token, note.toString(), Map.of()));
        ObjectNode draft = command().put("deckId", deck.id().toString());
        draft.set("document", document.deepCopy());
        JsonNode draftAck = json(HttpIdentityFixture.send(port, "POST", "/editing-drafts", token, draft.toString(), Map.of()));
        return new World(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)), UUID.fromString(head.path("revisionId").stringValue(null)),
                UUID.fromString(exercise.path("exerciseRevisionId").stringValue(null)), issued,
                UUID.fromString(noteAck.path("capture").path("noteId").stringValue(null)),
                UUID.fromString(draftAck.path("draft").path("draftId").stringValue(null)), asset, document);
    }

    private HttpResponse<String> call(Request request, UUID account) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (request.ifMatch() != null) headers.put("If-Match", request.ifMatch());
        return HttpIdentityFixture.send(port, request.method(), request.path(), HttpIdentityFixture.reader(account), request.body(), headers);
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    /** What the deck's rows look like: any change a stranger could have made shows here. */
    private String snapshot(World w) {
        UUID deck = w.deck().id();
        List<String> parts = new ArrayList<>();
        parts.add(jdbc.sql("SELECT to_jsonb(d)::text FROM app_learning.deck d WHERE deck_id = :deck").param("deck", deck).query(String.class).single());
        for (String table : new String[] {"deck_revision", "deck_head_item", "deck_item_change", "deck_item_exemplar", "deck_head_exercise", "exercise_revision",
                "deck_exercise_change", "memory_objective", "study_session", "editing_draft", "capture_note", "deck_publication", "deck_publication_event", "deck_access_grant",
                "generation_session"}) {
            parts.add(table + "=" + jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE deck_id = :deck").param("deck", deck).query(Long.class).single());
        }
        parts.add(jdbc.sql("SELECT string_agg(to_jsonb(n)::text, ',' ORDER BY note_id) FROM app_learning.capture_note n WHERE deck_id = :deck").param("deck", deck).query(String.class).single());
        parts.add(jdbc.sql("SELECT string_agg(to_jsonb(n)::text, ',' ORDER BY draft_id) FROM app_learning.editing_draft n WHERE deck_id = :deck").param("deck", deck).query(String.class).single());
        parts.add(jdbc.sql("SELECT count(*) FROM app_learning.learning_item WHERE reuse_scope_id = (SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id = :deck)").param("deck", deck).query(Long.class).single().toString());
        return String.join("\n", parts);
    }

    private void assertOpaque404(HttpResponse<String> response, String label) {
        assertThat(response.statusCode()).as(label + ": " + response.body()).isEqualTo(404);
        JsonNode body = json404(response);
        assertThat(body.path("code").stringValue(null)).as(label).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.body()).as(label).doesNotContain("Колода", "memory", "forgetting", "чужой текст", LibraryFixtures.SECRET_ANSWER, LibraryFixtures.SECRET_REFERENCE);
    }

    private static JsonNode json404(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    @Test
    void theTableCoversEveryDeckRouteOfTheRunningApplication() {
        Set<String> known = new TreeSet<>();
        rows().forEach(row -> known.add(row.route().replaceAll("\\?.*", "")));
        Set<String> discovered = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.contains("{deckId}") && !pattern.startsWith("/capture-notes") && !pattern.startsWith("/editing-drafts")
                        && !pattern.startsWith("/media-assets/{assetId}")) continue;
                for (RequestMethod method : info.getMethodsCondition().getMethods()) discovered.add(method + " " + pattern);
            }
        });
        // the account-level lists, the upload transport and the retries take no deck or an asset the caller reserved itself; they are owner-only by their own tests
        Set<String> accountLevel = Set.of("GET /capture-notes", "GET /editing-drafts", "POST /media-assets/{assetId}/processing/retry", "POST /media-assets/{assetId}/upload/retry",
                "POST /media-assets/{assetId}/upload/url", "GET /media-assets/{assetId}/upload/parts", "POST /media-assets/{assetId}/upload/part-urls",
                "POST /media-assets/{assetId}/upload/finalize", "DELETE /media-assets/{assetId}/upload");
        Set<String> missing = new TreeSet<>(discovered);
        missing.removeAll(known);
        missing.removeAll(accountLevel);
        assertThat(missing).as("deck routes without a row in the owner-only table").isEmpty();
        Set<String> stale = new TreeSet<>(known);
        stale.removeAll(discovered);
        stale.removeAll(Set.of("GET /capture-notes", "POST /capture-notes", "POST /editing-drafts"));
        assertThat(stale).as("rows that name no route").isEmpty();
    }

    @Test
    void aGranteeAndAnyAccountOnAnySharedDeckAreRefusedEverywhereWithTheOpaqueNotFound() {
        UUID grantee = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Map<String, UUID> strangers = new LinkedHashMap<>();
        strangers.put("INVITE", grantee);
        strangers.put("LINK", other);
        strangers.put("PUBLIC", other);
        List<Row> rows = rows();
        int executed = 0;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : strangers.entrySet()) {
            DeckVisibility level = DeckVisibility.valueOf(entry.getKey());
            World shared = world(level, entry.getKey().equals("INVITE") ? grantee : null);
            World ownersOwn = world(level, null);
            String before = snapshot(shared);
            for (Row row : rows) {
                String label = entry.getKey() + " stranger: " + row.route();
                // the same request by the owner of an identical deck reaches the domain
                HttpResponse<String> owned = call(row.request().apply(ownersOwn), ownersOwn.owner());
                if (owned.statusCode() != row.ownerMayGet() && Set.of(400, 401, 403, 404, 405, 415, 428, 500).contains(owned.statusCode())) {
                    problems.add("owner " + row.route() + " -> " + owned.statusCode() + " " + owned.body());
                }
                HttpResponse<String> response = call(row.request().apply(shared), entry.getValue());
                try {
                    assertOpaque404(response, label);
                } catch (AssertionError failure) {
                    problems.add(label + " -> " + response.statusCode() + " " + response.body());
                }
                executed++;
            }
            assertThat(snapshot(shared)).as("nothing of the " + entry.getKey() + " deck changed").isEqualTo(before);
            // and the owner still reads it
            assertThat(call(get("/decks/" + shared.deck().id()), shared.owner()).statusCode()).isEqualTo(200);
        }
        assertThat(problems).isEmpty();
        assertThat(executed).isEqualTo(rows.size() * 3);
    }

    @Test
    void aGuestIsRefusedByTheAuthenticationBoundaryOnEveryPrivateRoute() {
        World shared = world(DeckVisibility.PUBLIC, null);
        for (Row row : rows()) {
            Request request = row.request().apply(shared);
            Map<String, String> headers = new LinkedHashMap<>();
            if (request.ifMatch() != null) headers.put("If-Match", request.ifMatch());
            HttpResponse<String> response = HttpIdentityFixture.send(port, request.method(), request.path(), null, request.body(), headers);
            assertThat(response.statusCode()).as(row.route()).isEqualTo(401);
        }
    }

    @Test
    void thePublicRoutesNeverOfferAWrite() {
        // the only controller that serves non-owners has GET mappings only
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().equals(PublicDeckController.class)) return;
            assertThat(info.getMethodsCondition().getMethods()).containsOnly(RequestMethod.GET);
        });
        ArrayNode patterns = JSON.createArrayNode();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPathPatternsCondition().getPatternValues().forEach(patterns::add));
        assertThat(patterns.toString()).contains("/public/decks/{code}", "/public/decks/{code}/items", "/public/decks/{code}/items/{memberKey}", "/public/decks/{code}/exercises");
    }
}
