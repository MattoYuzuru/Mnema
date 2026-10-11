package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.DeckPublications;
import app.mnema.learning.library.GrantRole;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/10 (#432) through the real application: the copy command and its answers, the access matrix, idempotency, one live copy per (owner, source), the
 * heads written by the real job from the PUBLISHED manifest (with a slice size of 10, so every deck is several slices), independence of the copy and its source,
 * Study of a ready copy, copy of a copy, detach, {@code myCopy} and the {@code fork} member of the owner's reads. The answers are compared with the property sets of
 * {@code contracts/decks/forks.json}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class ForkHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonNode CONTRACT = ForkWorlds.forksContract();

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
    @Autowired private DeckPublications publications;
    private ForkWorlds worlds;
    private final UUID reader = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    private long count(String sql, Object... params) {
        var query = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) query = query.param("p" + i, params[i]);
        return query.query(Long.class).single();
    }

    // ---- the happy path ----

    @Test
    void aCopyIsMadeFromThePublishedRevisionAndItsHeadsAreWrittenByTheJobFromThePublishedManifest() {
        Source source = worlds.source("LINK", 25, 12);
        // the author keeps working after publishing: a new material, a new revision of a published one, a new exercise. None of it is published.
        StudyFixtures.Material unpublished = worlds.study.addMaterial(source.author(), source.deck(), "после", "публикации");
        worlds.edit(source.author(), source.deck(), source.materials().getFirst().member(), source.materials().getFirst().itemRevision(), "правка");
        worlds.study.publish(unpublished, worlds.study.freeResponse(unpublished, StudyFixtures.blocks(StudyFixtures.text("Новое?")), StudyFixtures.blocks(), "да"), "Новая цель");

        HttpResponse<String> response = worlds.fork(reader, source.code());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("cache-control")).contains("private, no-store");
        JsonNode created = json(response);
        assertThat(names(created)).isEqualTo(names(CONTRACT.path("create").path("created").path("body")));
        assertThat(created.path("preparing").booleanValue(false)).isTrue();
        assertThat(created.path("existing").booleanValue(true)).isFalse();
        UUID copy = UUID.fromString(created.path("deckId").stringValue(null));
        assertThat(response.headers().firstValue("location")).hasValueSatisfying(value -> assertThat(value).endsWith("/decks/" + copy));

        JsonNode ready = worlds.awaitReady(reader, copy);
        assertThat(names(ready.path("fork"))).isEqualTo(names(CONTRACT.path("fork").path("ready")));
        assertThat(ready.path("fork").path("sourceAvailable").booleanValue(false)).isTrue();
        assertThat(ready.path("fork").path("upstreamCode").stringValue(null)).isEqualTo(source.code());
        assertThat(ready.path("memberCount").intValue()).isEqualTo(25);
        assertThat(ready.path("exerciseCount").intValue()).isEqualTo(12);
        assertThat(ready.path("metadata").path("title").stringValue(null)).startsWith("Источник");

        // exactly the published manifest: 25 materials (not 26), the published revisions (not the edit), 12 exercises with their ordinals, their objectives
        worlds.assertHeadsEqualPublishedManifest(source.deck(), copy);
        assertThat(worlds.heads(copy).members()).hasSize(25);
        assertThat(worlds.heads(copy).objectives()).hasSize(12);
        assertThat(count("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id = :p0 AND member_key = :p1", copy, unpublished.member())).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id = :p0 AND revision_id = :p1", copy, source.materials().getFirst().itemRevision())).isEqualTo(1);

        // the job wrote nothing but heads: no Study row, exemplar, journal, draft, capture or workshop row of the copy
        for (String table : new String[] {"study_state", "study_session", "study_candidate", "deck_item_exemplar", "deck_item_change", "deck_exercise_change",
                "editing_draft", "capture_note", "generation_session", "exercise_new_mark"}) {
            assertThat(count("SELECT count(*) FROM app_learning." + table + " WHERE deck_id = :p0", copy)).as(table).isZero();
        }
        // the lineage is shared: no new material, exercise, objective or revision row exists for the copy
        assertThat(count("SELECT count(*) FROM app_learning.item_revision WHERE reuse_scope_id = (SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id = :p0) AND deck_id = :p0", copy)).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id = :p0", copy)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'deck.copy.materialize' AND dedupe_key = :p0 AND state = 'SUCCEEDED'", copy.toString())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT materialized_at IS NOT NULL AND detached_at IS NULL AND auto_update = false AND released_at IS NULL FROM app_learning.deck_fork WHERE deck_id = :d")
                .param("d", copy).query(Boolean.class).single()).isTrue();
    }

    @Test
    void theCopyAndItsSourceNeverReachEachOther() {
        Source source = worlds.source("LINK", 3, 2);
        UUID copy = worlds.forked(reader, source.code());
        worlds.awaitReady(reader, copy);
        StudyFixtures.Material first = source.materials().getFirst();
        UUID publishedHead = first.itemRevision();

        // the author edits after the fork: the copy still holds the published revision, and cannot read the new one
        UUID authorsEdit = worlds.edit(source.author(), source.deck(), first.member(), first.itemRevision(), "правка автора");
        assertThat(headRevision(copy, first.member())).isEqualTo(publishedHead);
        HttpResponse<String> hidden = worlds.get("/decks/" + copy + "/items/" + first.member() + "?revisionId=" + authorsEdit, reader);
        assertThat(hidden.statusCode()).isIn(400, 404);
        assertThat(hidden.body()).doesNotContain("правка автора");

        // the copy edits: the source still has its own head
        UUID copiesEdit = worlds.edit(reader, copy, first.member(), publishedHead, "правка копии");
        assertThat(headRevision(copy, first.member())).isEqualTo(copiesEdit);
        assertThat(headRevision(source.deck(), first.member())).isEqualTo(authorsEdit);
        assertThat(headRevision(source.deck(), first.member())).isNotEqualTo(copiesEdit);
        // and the author's reads never show the copy's revision
        assertThat(worlds.get("/decks/" + source.deck() + "/items/" + first.member() + "?revisionId=" + copiesEdit, source.author()).statusCode()).isIn(400, 404);
    }

    private UUID headRevision(UUID deck, UUID member) {
        return jdbc.sql("SELECT revision_id FROM app_learning.deck_head_item WHERE deck_id = :d AND member_key = :m").param("d", deck).param("m", member)
                .query(UUID.class).single();
    }

    @Test
    void aReadyCopyIsStudiedLikeAnyDeckAndProgressIsItsOwners() {
        Source source = worlds.source("LINK", 4, 3);
        UUID copy = worlds.forked(reader, source.code());
        worlds.awaitReady(reader, copy);
        StudyFixtures.Material inherited = source.materials().getFirst();
        StudyFixtures.Material asCopy = new StudyFixtures.Material(reader, copy, inherited.member(), inherited.itemRevision(), inherited.node(), inherited.distractor(),
                inherited.divider(), inherited.root());
        var presentations = worlds.study.issue(asCopy, "SCHEDULED", null);
        assertThat(presentations).isNotEmpty();
        assertThat(count("SELECT count(*) FROM app_learning.study_session WHERE deck_id = :p0 AND account_id = :p1", copy, reader)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM app_learning.study_session WHERE deck_id = :p0", source.deck())).isZero();
        // over HTTP too
        ObjectNode start = ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("mode", "PRACTICE").put("includeNew", true).put("order", "SEEDED");
        start.putObject("budget").put("maxPresentations", 5);
        HttpResponse<String> http = worlds.send("POST", "/decks/" + copy + "/study-sessions", reader, start.toString(), null);
        assertThat(http.statusCode()).as(http.body()).isBetween(200, 202);
    }

    // ---- idempotency and one live copy ----

    @Test
    void anExactRetryReplaysAndASecondRequestAnswersTheExistingCopy() {
        Source source = worlds.source("LINK", 2, 1);
        UUID command = UUID.randomUUID();
        HttpResponse<String> first = worlds.fork(reader, command, source.code());
        assertThat(first.statusCode()).isEqualTo(201);
        UUID copy = UUID.fromString(json(first).path("deckId").stringValue(null));

        HttpResponse<String> replay = worlds.fork(reader, command, source.code());
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(json(replay).path("deckId").stringValue(null)).isEqualTo(copy.toString());

        worlds.awaitReady(reader, copy);
        HttpResponse<String> second = worlds.fork(reader, source.code());
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        JsonNode existing = json(second);
        assertThat(names(existing)).isEqualTo(names(CONTRACT.path("create").path("existing").path("body")));
        assertThat(existing.path("existing").booleanValue(false)).isTrue();
        assertThat(existing.path("preparing").booleanValue(true)).isFalse();
        assertThat(existing.path("deckId").stringValue(null)).isEqualTo(copy.toString());
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :p0 AND upstream_deck_id = :p1", reader, source.deck())).isEqualTo(1);

        // the same commandId with another body is the house conflict
        Source other = worlds.source("LINK", 1, 0);
        problem(worlds.fork(reader, command, other.code()), 409, "IDEMPOTENCY_CONFLICT");
        // another account is not blocked by this account's copy
        assertThat(worlds.fork(UUID.randomUUID(), source.code()).statusCode()).isEqualTo(201);
    }

    @Test
    void twoRacingRequestsOfOneAccountMakeOneCopy() throws Exception {
        Source source = worlds.source("LINK", 2, 1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> worlds.fork(reader, source.code())));
            Set<String> decks = new TreeSet<>();
            int created = 0;
            for (var task : tasks) {
                HttpResponse<String> response = task.get();
                assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
                if (response.statusCode() == 201) created++;
                decks.add(json(response).path("deckId").stringValue(null));
            }
            assertThat(created).isEqualTo(1);
            assertThat(decks).hasSize(1);
        }
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :p0 AND upstream_deck_id = :p1 AND released_at IS NULL", reader, source.deck())).isEqualTo(1);
    }

    @Test
    void aDeletedCopyDoesNotCountAndTheOwnerMayCopyAgain() {
        Source source = worlds.source("LINK", 2, 1);
        UUID first = worlds.forked(reader, source.code());
        worlds.awaitReady(reader, first);
        JsonNode detail = json(worlds.get("/decks/" + first, reader));
        assertThat(worlds.send("DELETE", "/decks/" + first, reader, null, "\"" + detail.path("rowVersion").stringValue(null) + "\"").statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT released_at IS NOT NULL FROM app_learning.deck_fork WHERE deck_id = :d").param("d", first).query(Boolean.class).single()).isTrue();
        HttpResponse<String> again = worlds.fork(reader, source.code());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(json(again).path("deckId").stringValue(null)).isNotEqualTo(first.toString());
        assertThat(worlds.get("/decks/" + first, reader).statusCode()).isEqualTo(404);
    }

    // ---- who may copy ----

    @Test
    void theOwnerOfTheSourceCannotCopyItAndAStrangerGetsTheOpaqueNotFound() {
        Source source = worlds.source("LINK", 2, 1);
        problem(worlds.fork(source.author(), source.code()), 409, "FORK_OWN_DECK");
        assertThat(names(json(worlds.fork(source.author(), source.code())))).isEqualTo(names(CONTRACT.path("problems").path("ownDeck").path("body")));
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE upstream_deck_id = :p0", source.deck())).isZero();

        String oldCode = source.code();
        // unknown, malformed and empty codes are the same 404 as a private deck
        problem(worlds.fork(reader, "zzzzzzzzzz"), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.fork(reader, "short"), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.fork(reader, "not a code at all!"), 404, "RESOURCE_NOT_FOUND");
        JsonNode notFound = json(worlds.fork(reader, "zzzzzzzzzz"));
        assertThat(names(notFound)).isEqualTo(names(CONTRACT.path("problems").path("notFound").path("body")));

        // a private deck: the level goes back, the code is rotated away; the old code and the deck are both 404
        String privateCode = worlds.level(source.author(), source.deck(), "PRIVATE");
        assertThat(privateCode).isNull();
        problem(worlds.fork(reader, oldCode), 404, "RESOURCE_NOT_FOUND");
        // shared again: a new code, the old one stays dead
        String fresh = worlds.level(source.author(), source.deck(), "LINK");
        assertThat(fresh).isNotEqualTo(oldCode);
        problem(worlds.fork(reader, oldCode), 404, "RESOURCE_NOT_FOUND");
        assertThat(worlds.fork(reader, fresh).statusCode()).isEqualTo(201);

        // a deck that was never published
        UUID unpublished = worlds.shared.deck(source.author(), "Не опубликована");
        assertThat(count("SELECT count(*) FROM app_learning.deck_publication WHERE deck_id = :p0", unpublished)).isZero();
        // a deleted source
        Source gone = worlds.source("LINK", 1, 0);
        String goneCode = gone.code();
        JsonNode detail = json(worlds.get("/decks/" + gone.deck(), gone.author()));
        assertThat(worlds.send("DELETE", "/decks/" + gone.deck(), gone.author(), null, "\"" + detail.path("rowVersion").stringValue(null) + "\"").statusCode()).isEqualTo(204);
        problem(worlds.fork(reader, goneCode), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void anInvitationDeckIsCopiedByAGranteeOnly() {
        Source source = worlds.source("INVITE", 3, 1);
        HttpResponse<String> refused = worlds.fork(reader, source.code());
        problem(refused, 403, "DECK_INVITE_ONLY");
        assertThat(names(json(refused))).isEqualTo(names(CONTRACT.path("problems").path("inviteOnly").path("body")));
        assertThat(refused.body()).doesNotContain("Источник");
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :p0", reader)).isZero();

        publications.grant(source.author(), source.deck(), reader, GrantRole.VIEWER);
        HttpResponse<String> allowed = worlds.fork(reader, source.code());
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(201);
        UUID copy = UUID.fromString(json(allowed).path("deckId").stringValue(null));
        worlds.awaitReady(reader, copy);
        worlds.assertHeadsEqualPublishedManifest(source.deck(), copy);

        // revoking the grant later cuts the copier off from the source, not from his copy
        publications.revoke(source.author(), source.deck(), reader);
        JsonNode detail = json(worlds.get("/decks/" + copy, reader));
        assertThat(detail.path("fork").path("sourceAvailable").booleanValue(true)).isFalse();
        assertThat(detail.path("memberCount").intValue()).isEqualTo(3);
        assertThat(worlds.get("/decks/" + copy + "/items", reader).statusCode()).isEqualTo(200);
    }

    @Test
    void aPublicDeckAndACopyOfACopy() {
        Source source = worlds.source("PUBLIC", 12, 5);
        UUID middle = UUID.randomUUID();
        UUID copy = worlds.forked(middle, source.code());
        worlds.awaitReady(middle, copy);
        // the copy's owner edits it, then publishes it (a copy is publishable once it is ready)
        StudyFixtures.Material first = source.materials().getFirst();
        UUID edit = worlds.edit(middle, copy, first.member(), first.itemRevision(), "редакция копии");
        HttpIdentityFixture.publicProfile(middle, true);
        String copiesCode = worlds.publish(middle, copy, "LINK");

        UUID third = UUID.randomUUID();
        UUID grandchild = worlds.forked(third, copiesCode);
        worlds.awaitReady(third, grandchild);
        // the grandchild is a copy of the copy's published revision: the edit and everything else
        worlds.assertHeadsEqualPublishedManifest(copy, grandchild);
        assertThat(headRevision(grandchild, first.member())).isEqualTo(edit);
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE deck_id = :p0 AND upstream_deck_id = :p1", grandchild, copy)).isEqualTo(1);
        // all three live in one lineage
        assertThat(count("SELECT count(DISTINCT reuse_scope_id) FROM app_learning.deck WHERE deck_id IN (:p0, :p1)", source.deck(), grandchild)).isEqualTo(1);
    }

    // ---- reads ----

    @Test
    void theOwnersReadsCarryTheForkMemberAndASourceThatClosesGoesDark() {
        Source source = worlds.source("LINK", 3, 1);
        UUID plain = worlds.shared.deck(reader, "Обычная");
        UUID copy = worlds.forked(reader, source.code());
        worlds.awaitReady(reader, copy);

        JsonNode ordinary = json(worlds.get("/decks/" + plain, reader));
        assertThat(ordinary.has("fork")).isTrue();
        assertThat(ordinary.path("fork").isNull()).isTrue();
        JsonNode listed = json(worlds.get("/decks?limit=100", reader));
        JsonNode copyRow = null;
        JsonNode plainRow = null;
        for (JsonNode row : listed.path("items")) {
            if (row.path("deckId").stringValue("").equals(copy.toString())) copyRow = row;
            if (row.path("deckId").stringValue("").equals(plain.toString())) plainRow = row;
        }
        assertThat(copyRow).isNotNull();
        assertThat(names(copyRow.path("fork"))).isEqualTo(names(CONTRACT.path("fork").path("ready")));
        assertThat(plainRow.path("fork").isNull()).isTrue();

        // the author makes the deck private: the copier's link goes dark; the copy stays whole
        worlds.level(source.author(), source.deck(), "PRIVATE");
        JsonNode closed = json(worlds.get("/decks/" + copy, reader));
        assertThat(closed.path("fork").toString()).isEqualTo(CONTRACT.path("fork").path("sourceClosed").toString());
        assertThat(closed.toString()).doesNotContain(source.code());
        assertThat(worlds.heads(copy).members()).hasSize(3);

        // lowering PUBLIC to LINK rotates the code and the copier is never handed the new one
        Source other = worlds.source("PUBLIC", 2, 0);
        UUID second = worlds.forked(reader, other.code());
        worlds.awaitReady(reader, second);
        assertThat(json(worlds.get("/decks/" + second, reader)).path("fork").path("sourceAvailable").booleanValue(false)).isTrue();
        String rotated = worlds.level(other.author(), other.deck(), "LINK");
        JsonNode lowered = json(worlds.get("/decks/" + second, reader));
        assertThat(lowered.path("fork").path("sourceAvailable").booleanValue(true)).isFalse();
        assertThat(lowered.toString()).doesNotContain(rotated).doesNotContain(other.code());
    }

    @Test
    void theHubOfAPreparingCopyAnswersAnEmptyListAndTheMyCopyMemberOfThePublicSummaryFollowsIt() {
        Source source = worlds.source("LINK", 4, 1);
        // the viewer has no copy: null for him, no member for a guest, null for the owner of the deck
        assertThat(worlds.get("/public/decks/" + source.code(), reader).statusCode()).isEqualTo(200);
        JsonNode before = json(worlds.get("/public/decks/" + source.code(), reader));
        assertThat(before.has("myCopy")).isTrue();
        assertThat(before.path("myCopy").isNull()).isTrue();
        assertThat(json(worlds.get("/public/decks/" + source.code(), null)).has("myCopy")).isFalse();
        assertThat(json(worlds.get("/public/decks/" + source.code(), source.author())).path("myCopy").isNull()).isTrue();

        UUID copy = worlds.forked(reader, source.code());
        JsonNode mine = json(worlds.get("/public/decks/" + source.code(), reader)).path("myCopy");
        assertThat(names(mine)).isEqualTo(names(CONTRACT.path("myCopy").path("preparing")));
        assertThat(mine.path("deckId").stringValue(null)).isEqualTo(copy.toString());
        worlds.awaitReady(reader, copy);
        JsonNode done = json(worlds.get("/public/decks/" + source.code(), reader)).path("myCopy");
        assertThat(done.path("preparing").booleanValue(true)).isFalse();
        // somebody else's view is unaffected
        assertThat(json(worlds.get("/public/decks/" + source.code(), UUID.randomUUID())).path("myCopy").isNull()).isTrue();
        // a deleted copy is no longer mine
        JsonNode detail = json(worlds.get("/decks/" + copy, reader));
        worlds.send("DELETE", "/decks/" + copy, reader, null, "\"" + detail.path("rowVersion").stringValue(null) + "\"");
        assertThat(json(worlds.get("/public/decks/" + source.code(), reader)).path("myCopy").isNull()).isTrue();
    }

    // ---- detach and retry ----

    @Test
    void detachingIsIdempotentOwnerOnlyAndKeepsTheContent() {
        Source source = worlds.source("LINK", 3, 1);
        UUID copy = worlds.forked(reader, source.code());
        worlds.awaitReady(reader, copy);
        HttpResponse<String> detached = worlds.send("POST", "/decks/" + copy + "/fork:detach", reader, null, null);
        assertThat(detached.statusCode()).as(detached.body()).isEqualTo(200);
        assertThat(json(detached).toString()).isEqualTo(CONTRACT.path("detach").path("body").toString().replace(
                CONTRACT.path("detach").path("body").path("deckId").stringValue(null), copy.toString()));
        long at = jdbc.sql("SELECT extract(epoch FROM detached_at)::bigint FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(Long.class).single();
        assertThat(worlds.send("POST", "/decks/" + copy + "/fork:detach", reader, null, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT extract(epoch FROM detached_at)::bigint FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(Long.class).single()).isEqualTo(at);
        JsonNode detail = json(worlds.get("/decks/" + copy, reader));
        assertThat(detail.path("fork").path("detached").booleanValue(false)).isTrue();
        assertThat(names(detail.path("fork"))).isEqualTo(names(CONTRACT.path("fork").path("detached")));
        assertThat(worlds.heads(copy).members()).hasSize(3);
        // a stranger and an ordinary deck: the opaque 404
        problem(worlds.send("POST", "/decks/" + copy + "/fork:detach", UUID.randomUUID(), null, null), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.send("POST", "/decks/" + source.deck() + "/fork:detach", source.author(), null, null), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.send("POST", "/decks/" + copy + "/fork:retry", UUID.randomUUID(), null, null), 404, "RESOURCE_NOT_FOUND");
        // retry of a ready copy changes nothing
        HttpResponse<String> retry = worlds.send("POST", "/decks/" + copy + "/fork:retry", reader, null, null);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(json(retry).path("preparing").booleanValue(true)).isFalse();
        assertThat(names(json(retry))).isEqualTo(names(CONTRACT.path("retry").path("body")));
    }
}
