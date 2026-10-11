package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/10 (#432), the preparing guard. No executor runs in this context (the default test role is {@code api}), so a copy stays exactly as the command left it: its job
 * queued, its heads empty. Every mutation route of the running application and Study start must answer {@code 409 DECK_PREPARING} to the OWNER of such a copy, the same
 * requests must answer anything but that to a stranger and to the owner of an ordinary deck and, once the copy is ready, to its owner. The table is the route inventory of
 * the application itself, so a new route under {@code /decks/{deckId}} cannot ship unguarded. The reads of the hub must keep answering, and the database refuses a head
 * write on a preparing copy by itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"learning.community.forks.max-preparing=3"})
class ForkPreparingGuardHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
    private ForkWorlds worlds;
    private final UUID owner = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    @BeforeEach
    void worlds() {
        worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private record Request(String method, String path, String body) { }

    /** Every mutation route under /decks/{deckId} of the running application, addressed to {@code deck} with ids that do not exist. */
    private List<Request> mutationRoutes(UUID deck) {
        List<Request> result = new ArrayList<>();
        Set<String> exempt = Set.of("POST /decks/{deckId}/fork:retry", "POST /decks/{deckId}/fork:detach", "DELETE /decks/{deckId}");
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/decks/{deckId}")) continue;
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    if (method == RequestMethod.GET || method == RequestMethod.HEAD || exempt.contains(method + " " + pattern)) continue;
                    String path = pattern.replace("{deckId}", deck.toString()).replace("{slotKey}", "slot").replaceAll("\\{[A-Za-z]+}", UUID.randomUUID().toString());
                    result.add(new Request(method.name(), path, method == RequestMethod.DELETE ? null : "{}"));
                }
            }
        });
        return result;
    }

    private List<Request> bodyAddressed(UUID deck) {
        ObjectNode note = ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("deckId", deck.toString())
                .put("source", "s").put("text", "t");
        ObjectNode draft = ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("deckId", deck.toString());
        draft.set("document", worlds.shared.document("черновик"));
        return List.of(new Request("POST", "/capture-notes", note.toString()), new Request("POST", "/editing-drafts", draft.toString()));
    }

    private HttpResponse<String> send(Request request, UUID account) {
        return worlds.send(request.method(), request.path(), account, request.body(), request.body() == null ? null : "\"0\"");
    }

    private static String code(HttpResponse<String> response) {
        try { return json(response).path("code").stringValue(""); } catch (AssertionError notJson) { return ""; }
    }

    private UUID preparingCopy(Source source) {
        UUID copy = worlds.forked(owner, source.code());
        assertThat(jdbc.sql("SELECT state FROM app_learning.learning_job WHERE queue = 'deck.copy.materialize' AND dedupe_key = :k").param("k", copy.toString())
                .query(String.class).single()).isEqualTo("QUEUED");
        return copy;
    }

    @Test
    void everyMutationRouteAndStudyStartAnswersDeckPreparingToTheOwnerOfAPreparingCopyAndNothingElse() {
        Source source = worlds.source("LINK", 3, 2);
        UUID copy = preparingCopy(source);
        List<Request> routes = new ArrayList<>(mutationRoutes(copy));
        routes.addAll(bodyAddressed(copy));
        // the inventory is read from the application: items, exercises, Study, restarts, generation, publication, exemplar and deletion routes are all in it
        assertThat(routes.stream().map(Request::path).filter(path -> path.endsWith("/study-sessions")).count()).isEqualTo(1);
        Set<String> templates = new TreeSet<>();
        routes.forEach(request -> templates.add(request.method() + " " + request.path().replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "{id}")));
        assertThat(templates).contains("PATCH /decks/{id}", "PUT /decks/{id}/publication", "POST /decks/{id}/items", "PUT /decks/{id}/items/{id}",
                "POST /decks/{id}/items/publications", "POST /decks/{id}/items/{id}/exemplar", "POST /decks/{id}/items/deletions",
                "POST /decks/{id}/exercises", "PUT /decks/{id}/exercises/{id}", "DELETE /decks/{id}/exercises/{id}", "POST /decks/{id}/study-sessions",
                "POST /decks/{id}/study-sessions/{id}/attempts", "POST /decks/{id}/study-restarts", "POST /decks/{id}/generation-sessions",
                "POST /decks/{id}/generation-intents", "POST /capture-notes", "POST /editing-drafts");
        assertThat(templates).hasSizeGreaterThan(30).doesNotContain("DELETE /decks/{id}", "POST /decks/{id}/fork:retry", "POST /decks/{id}/fork:detach");

        for (Request request : routes) {
            HttpResponse<String> response = send(request, owner);
            assertThat(response.statusCode()).as("owner: " + request.method() + " " + request.path() + " -> " + response.body()).isEqualTo(409);
            assertThat(code(response)).as(request.method() + " " + request.path()).isEqualTo("DECK_PREPARING");
            assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        }
        // nothing was written by any of them
        assertThat(count("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id = :d", copy)).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.editing_draft WHERE deck_id = :d", copy)).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.capture_note WHERE deck_id = :d", copy)).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.study_session WHERE deck_id = :d", copy)).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id = :d", copy)).isEqualTo(1);

        // a stranger addressing the same deck id gets the ordinary answers of the owner-only routes, never the owner's 409 (it would confirm that the deck exists)
        UUID stranger = UUID.randomUUID();
        for (Request request : routes) {
            HttpResponse<String> response = send(request, stranger);
            assertThat(code(response)).as("stranger: " + request.method() + " " + request.path()).isNotEqualTo("DECK_PREPARING");
            assertThat(response.statusCode()).as("stranger: " + request.method() + " " + request.path()).isNotEqualTo(409);
        }
        // and the owner of an ordinary deck of his own
        UUID plain = worlds.shared.deck(owner, "Обычная");
        for (Request request : mutationRoutes(plain)) {
            assertThat(code(send(request, owner))).as("ordinary deck: " + request.method() + " " + request.path()).isNotEqualTo("DECK_PREPARING");
        }

        // the copy becomes ready: the guard lets every request through to its own validation
        jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = statement_timestamp() WHERE deck_id = :d").param("d", copy).update();
        for (Request request : routes) {
            assertThat(code(send(request, owner))).as("ready: " + request.method() + " " + request.path()).isNotEqualTo("DECK_PREPARING");
        }
    }

    @Test
    void theHubOfAPreparingCopyKeepsAnsweringWithItsPreparingState() {
        Source source = worlds.source("LINK", 3, 2);
        UUID copy = preparingCopy(source);
        JsonNode detail = json(worlds.get("/decks/" + copy, owner));
        assertThat(detail.path("fork").path("preparing").booleanValue(false)).isTrue();
        assertThat(detail.path("fork").path("failed").booleanValue(true)).isFalse();
        assertThat(detail.path("memberCount").intValue()).isEqualTo(3);
        assertThat(detail.path("exerciseCount").intValue()).isEqualTo(2);
        JsonNode list = json(worlds.get("/decks/" + copy + "/items", owner));
        assertThat(list.path("items")).isEmpty();
        assertThat(list.path("total").intValue()).isEqualTo(3);
        assertThat(list.path("nextCursor").isNull()).isTrue();
        for (String read : List.of("/decks", "/decks/" + copy + "/exercises", "/decks/" + copy + "/insights", "/decks/" + copy + "/study-progress",
                "/decks/" + copy + "/publication", "/decks/" + copy + "/study-sessions/replay-sources", "/decks/" + copy + "/media-manifests/current",
                "/decks/" + copy + "/generation-sessions")) {
            HttpResponse<String> response = worlds.get(read, owner);
            assertThat(response.statusCode()).as(read + " -> " + response.body()).isEqualTo(200);
        }
        assertThat(worlds.get("/decks/" + copy + "/items/" + source.materials().getFirst().member(), owner).statusCode()).isEqualTo(404);
        // the public summary of the source already points the viewer at his preparing copy
        assertThat(json(worlds.get("/public/decks/" + source.code(), owner)).path("myCopy").path("preparing").booleanValue(false)).isTrue();
    }

    @Test
    void aStuckCopyCanBeDetachedRetriedAndDeletedWhilePreparing() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = preparingCopy(source);
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:detach", owner, null, null).statusCode()).isEqualTo(200);
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:retry", owner, null, null).statusCode()).isEqualTo(200);
        assertThat(json(worlds.get("/decks/" + copy, owner)).path("fork").path("detached").booleanValue(false)).isTrue();
        assertThat(worlds.send("DELETE", "/decks/" + copy, owner, null, "\"0\"").statusCode()).isEqualTo(204);
        assertThat(worlds.get("/decks/" + copy, owner).statusCode()).isEqualTo(404);
        // the slot of the (owner, source) pair is free again at once
        assertThat(worlds.fork(owner, source.code()).statusCode()).isEqualTo(201);
    }

    @Test
    void theDatabaseRefusesAHeadWriteOnAPreparingCopyOutsideTheJob() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = preparingCopy(source);
        // a service call that bypasses HTTP: the head of the deck cannot advance
        assertThatThrownBy(() -> worlds.shared.create(owner, copy, worlds.shared.document("новое")))
                .hasStackTraceContaining("still being prepared");
        // direct head writes
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO app_learning.deck_head_item(deck_id, reuse_scope_id, member_key, revision_id, item_sequence, updated_at)
                SELECT :copy, reuse_scope_id, member_key, revision_id, item_sequence, now() FROM app_learning.deck_head_item WHERE deck_id = :source
                """).param("copy", copy).param("source", source.deck()).update()).hasStackTraceContaining("still being prepared");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO app_learning.deck_head_exercise(deck_id, reuse_scope_id, exercise_id, revision_id, exercise_sequence, ordinal, updated_at)
                SELECT :copy, reuse_scope_id, exercise_id, revision_id, exercise_sequence, ordinal, now() FROM app_learning.deck_head_exercise WHERE deck_id = :source
                """).param("copy", copy).param("source", source.deck()).update()).hasStackTraceContaining("still being prepared");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO app_learning.objective_head(deck_id, reuse_scope_id, objective_id, revision_id, objective_sequence, updated_at)
                SELECT :copy, reuse_scope_id, objective_id, revision_id, objective_sequence, now() FROM app_learning.objective_head WHERE deck_id = :source
                """).param("copy", copy).param("source", source.deck()).update()).hasStackTraceContaining("still being prepared");
        // naming ANOTHER deck as the one being materialized does not help
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SELECT set_config('mnema.fork_materializing', :other, true)").param("other", UUID.randomUUID().toString()).query().listOfRows();
            jdbc.sql("INSERT INTO app_learning.deck_head_item(deck_id, reuse_scope_id, member_key, revision_id, item_sequence, updated_at) "
                    + "SELECT :copy, reuse_scope_id, member_key, revision_id, item_sequence, now() FROM app_learning.deck_head_item WHERE deck_id = :source")
                    .param("copy", copy).param("source", source.deck()).update();
        })).hasStackTraceContaining("still being prepared");
        assertThat(count("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id = :d", copy)).isZero();
        // a ready copy and an ordinary deck write their heads as always
        jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = statement_timestamp() WHERE deck_id = :d").param("d", copy).update();
        worlds.shared.create(owner, copy, worlds.shared.document("после готовности"));
        assertThat(count("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id = :d", copy)).isEqualTo(1);
    }

    @Test
    void aFailedCopyIsReportedAndRetriedUnderTheSameJobOrANewOneOncePurged() {
        Source source = worlds.source("LINK", 2, 1);
        UUID copy = preparingCopy(source);
        UUID job = jdbc.sql("SELECT job_id FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(UUID.class).single();
        jdbc.sql("UPDATE app_learning.learning_job SET state = 'FAILED', finished_at = now(), last_error = 'manifest_unresolved' WHERE job_id = :j").param("j", job).update();
        JsonNode failed = json(worlds.get("/decks/" + copy, owner)).path("fork");
        assertThat(failed.path("preparing").booleanValue(false)).isTrue();
        assertThat(failed.path("failed").booleanValue(false)).isTrue();
        assertThat(json(worlds.get("/decks", owner)).path("items").get(0).path("fork").path("failed").booleanValue(false)).isTrue();
        // every mutation still waits
        assertThat(worlds.send("PATCH", "/decks/" + copy, owner, "{}", "\"0\"").statusCode()).isEqualTo(409);

        HttpResponse<String> retry = worlds.send("POST", "/decks/" + copy + "/fork:retry", owner, null, null);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(json(retry).path("failed").booleanValue(true)).isFalse();
        assertThat(jdbc.sql("SELECT state || '/' || attempts FROM app_learning.learning_job WHERE job_id = :j").param("j", job).query(String.class).single()).isEqualTo("QUEUED/0");
        assertThat(json(worlds.get("/decks/" + copy, owner)).path("fork").path("failed").booleanValue(true)).isFalse();
        // a retry of a copy whose job is queued or running changes nothing
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:retry", owner, null, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'deck.copy.materialize' AND dedupe_key = :k").param("k", copy.toString())
                .query(Long.class).single()).isEqualTo(1);

        // retention deleted the job's record: the copy reads as failed and a retry enqueues a fresh job under the same key
        jdbc.sql("DELETE FROM app_learning.learning_job WHERE job_id = :j").param("j", job).update();
        assertThat(json(worlds.get("/decks/" + copy, owner)).path("fork").path("failed").booleanValue(false)).isTrue();
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:retry", owner, null, null).statusCode()).isEqualTo(200);
        UUID replacement = jdbc.sql("SELECT job_id FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(UUID.class).single();
        assertThat(replacement).isNotEqualTo(job);
        assertThat(jdbc.sql("SELECT state FROM app_learning.learning_job WHERE job_id = :j").param("j", replacement).query(String.class).single()).isEqualTo("QUEUED");
        assertThat(json(worlds.get("/decks/" + copy, owner)).path("fork").path("failed").booleanValue(true)).isFalse();
    }

    @Test
    void anAccountCannotHoldMoreThanTheCapOfUnfinishedCopies() {
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < 4; i++) sources.add(worlds.source("LINK", 1, 0));
        List<UUID> copies = new ArrayList<>();
        for (int i = 0; i < 3; i++) copies.add(worlds.forked(owner, sources.get(i).code()));
        HttpResponse<String> refused = worlds.fork(owner, sources.get(3).code());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        JsonNode problem = json(refused);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("RESOURCE_LIMIT_EXCEEDED");
        assertThat(problem.path("limit").intValue()).isEqualTo(3);
        assertThat(new TreeSet<>(problem.propertyNames())).isEqualTo(new TreeSet<>(ForkWorlds.forksContract().path("problems").path("limit").path("body").propertyNames().stream().toList()));
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :d", owner)).isEqualTo(3);
        // the copy he already has is still answered (no new work), and other accounts are not limited by his queue
        assertThat(worlds.fork(owner, sources.get(0).code()).statusCode()).isEqualTo(200);
        assertThat(worlds.fork(UUID.randomUUID(), sources.get(3).code()).statusCode()).isEqualTo(201);
        // a deleted copy frees a place; a ready one never counted
        assertThat(worlds.send("DELETE", "/decks/" + copies.getFirst(), owner, null, "\"0\"").statusCode()).isEqualTo(204);
        assertThat(worlds.fork(owner, sources.get(3).code()).statusCode()).isEqualTo(201);
        jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = statement_timestamp() WHERE deck_id = :d").param("d", copies.get(1)).update();
        assertThat(worlds.fork(owner, sources.get(0).code()).statusCode()).isEqualTo(201);
    }

    private long count(String sql, Object... params) {
        var query = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) query = query.param(i == 0 ? "d" : "p" + i, params[i]);
        return query.query(Long.class).single();
    }

    @Test
    void parallelRequestsForDifferentSourcesCannotOvershootTheCapOfUnfinishedCopies() throws Exception {
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < 8; i++) sources.add(worlds.source("LINK", 1, 0));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (Source source : sources) {
                tasks.add(pool.submit(() -> {
                    gate.await();
                    return worlds.fork(owner, source.code());
                }));
            }
            gate.countDown();
            int created = 0;
            int refused = 0;
            for (var task : tasks) {
                HttpResponse<String> response = task.get();
                if (response.statusCode() == 201) created++;
                else if (response.statusCode() == 422) refused++;
                else throw new AssertionError(response.statusCode() + " " + response.body());
            }
            // the cap of this class is 3: the account lock makes the count and the insert one step
            assertThat(created).isEqualTo(3);
            assertThat(refused).isEqualTo(5);
        }
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :d AND materialized_at IS NULL AND released_at IS NULL", owner)).isEqualTo(3);
    }
}
