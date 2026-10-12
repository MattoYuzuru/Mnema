package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.plan.PlanWorlds.Authored;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.update.plan.PlanWorlds.JSON;
import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Updates/4 (#443): what the apply command does to the rest of the product and which copies it refuses. Study after an apply (the next issues use the new heads, a presentation
 * already shown keeps its snapshot, a changed answer keeps the schedule), the media of an adopted material reaching its copier, and the states and the access matrix: a stranger, a
 * deck that is not a copy, a preparing copy, a detached one, a source nobody can read any more.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class ApplyStatesIntegrationTest extends PostgresIntegrationTest {
    private static final JsonNode CONTRACT = PlanWorlds.updatesContract();

    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private AttemptService attempts;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired private UpdatePlanService service;
    @Autowired private DeckModeration moderation;
    private PlanWorlds worlds;
    private ApplyWorlds apply;

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
        worlds = new PlanWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
        apply = new ApplyWorlds(worlds, manifests, service);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).path("code").stringValue("")).isEqualTo(code);
    }

    /** The problem is the contract's example: the same members, type, title, status, detail and code. */
    private static void asInTheContract(HttpResponse<String> response, String name) {
        JsonNode expected = CONTRACT.path("problems").path(name).path("body");
        assertThat(response.statusCode()).as(name).isEqualTo(CONTRACT.path("problems").path(name).path("status").intValue());
        JsonNode actual = json(response);
        java.util.Set<String> actualNames = new java.util.TreeSet<>();
        actual.propertyNames().forEach(actualNames::add);
        java.util.Set<String> expectedNames = new java.util.TreeSet<>();
        expected.propertyNames().forEach(expectedNames::add);
        assertThat(actualNames).as(name).isEqualTo(expectedNames);
        for (String field : new String[] {"type", "title", "status", "detail", "code"}) assertThat(actual.path(field)).as(name + "." + field).isEqualTo(expected.path(field));
    }

    private String frozen(String table, UUID account) {
        return jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM app_learning." + table + " t WHERE t.account_id=:a")
                .param("a", account).query(String.class).single();
    }

    // ---- Study ----

    @Test
    void studyUsesTheNewHeadsAfterAnApplyAPresentationAlreadyShownKeepsItsSnapshotAndAChangedAnswerKeepsTheSchedule() {
        Source source = worlds.source("LINK", 3, 1);
        Material m0 = source.materials().get(0);
        Authored e0 = source.exercises().get(0);
        Copy copy = worlds.copy(source);
        UUID learner = copy.owner();
        Material mine = worlds.head(learner, copy.deck(), m0);

        // the learner answers the exercise (it is scheduled now) and has another presentation open
        Issued answered = worlds.study.issueOne(mine);
        assertThat(answered.json().path("exerciseRevisionId").stringValue(null)).isEqualTo(e0.revisionId().toString());
        attempts.submit(learner, copy.deck(), answered.session(), StudyFixtures.attempt(answered, StudyFixtures.textResponse("перевод 0")));
        Issued shown = worlds.study.issue(mine, "PRACTICE", null).getFirst();
        assertThat(shown.json().path("exerciseRevisionId").stringValue(null)).isEqualTo(e0.revisionId().toString());
        String schedule = frozen("study_state", learner);
        assertThat(schedule).isNotEqualTo("[]");

        // the author changes the question AND the accepted answer of the exercise; its objective stays the same one
        Material authorsM0 = worlds.head(source.author(), source.deck(), m0);
        ObjectNode exercise = worlds.study.freeResponse(authorsM0, blocks(text("Новый вопрос автора")), blocks(), "совсем другой ответ");
        ObjectNode body = worlds.study.createBody(authorsM0, exercise, "ignored");
        body.set("objective", JSON.createObjectNode().put("operation", "reuse").put("objectiveId", e0.objectiveId().toString())
                .put("objectiveRevisionId", e0.objectiveRevisionId().toString()));
        body.put("expectedExerciseRevisionId", e0.revisionId().toString());
        Authored revised = Authored.of(exercises.publish(source.author(), source.deck(), e0.exerciseId(), worlds.study.deckVersion(authorsM0),
                ExerciseCommand.readUpdate(bytes(body))).acknowledgement());
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(apply.plan(copy).units()).extracting(PlanUnit::unitId).containsExactly(UnitKind.EXERCISE.unitId(e0.exerciseId()));

        HttpResponse<String> response = apply.apply(copy, true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(apply.exerciseHead(copy.deck(), e0.exerciseId())).isEqualTo(revised.revisionId());

        // the schedule is the same rows: an update of an exercise is not a reset of what the learner knows (progress is kept by the objective)
        assertThat(frozen("study_state", learner)).isEqualTo(schedule);
        // the presentation that was already shown is still the exercise the learner was shown
        JsonNode old = sessions.read(learner, copy.deck(), shown.session()).path("presentations").get(0);
        assertThat(old.path("exerciseRevisionId").stringValue(null)).isEqualTo(e0.revisionId().toString());
        assertThat(old.toString()).contains("Что значит 0?").doesNotContain("Новый вопрос автора");
        // and the next issues use the new heads
        Issued next = worlds.study.issue(mine, "PRACTICE", null).getFirst();
        assertThat(next.json().path("exerciseRevisionId").stringValue(null)).isEqualTo(revised.revisionId().toString());
        assertThat(next.json().toString()).contains("Новый вопрос автора");
        // the learner answers the new exercise with the new answer; the objective is still the same one, with its history
        attempts.submit(learner, copy.deck(), next.session(), StudyFixtures.attempt(next, StudyFixtures.textResponse("совсем другой ответ")));
        assertThat(jdbc.sql("SELECT count(DISTINCT objective_id) FROM app_learning.study_state WHERE account_id=:a").param("a", learner).query(Long.class).single()).isEqualTo(1L);
    }

    // ---- media ----

    @Test
    void theMediaOfAnAdoptedMaterialIsReadableByItsCopierOnlyAfterTheApply() {
        Source source = worlds.source("LINK", 2, 0);
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID asset = worlds.study.readyAsset(author, "image/png");
        MediaFixtures.Variants variants = MediaFixtures.variants(jdbc, asset, "image/webp");
        SharedScopeFixture.Material picture = worlds.shared.create(author, source.deck(), MediaFixtures.imageDocument(asset));
        worlds.publish(author, source.deck(), "LINK");

        assertThatThrownBy(() -> media.playback(copy.owner(), asset)).as("not the copier's before he takes the material").isInstanceOf(ResourceNotFoundException.class);
        HttpResponse<String> response = apply.apply(copy, true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(worlds.itemHead(copy.deck(), picture.member())).isEqualTo(picture.revision());
        assertThat(media.playback(copy.owner(), asset).playable().objectKey()).as("through the copy grant, derived variants only").isEqualTo(variants.playbackKey());
        assertThat(media.playback(copy.owner(), asset).original()).isNull();
    }

    // ---- who may apply what ----

    private Copy copyWithAChange(Source source) {
        Copy copy = worlds.copy(source);
        worlds.editText(source.author(), source.deck(), source.materials().get(0).member(), "автор " + UUID.randomUUID());
        worlds.publish(source.author(), source.deck(), "LINK");
        return copy;
    }

    @Test
    void aStrangerAndADeckThatIsNotACopyAreTheOpaqueNotFoundAndNothingIsWritten() {
        Source source = worlds.source("LINK", 3, 0);
        Copy copy = copyWithAChange(source);
        String version = apply.version(copy);
        String snapshot = apply.snapshot(copy.deck());
        UUID stranger = UUID.randomUUID();
        for (UUID account : List.of(stranger, source.author())) {
            HttpResponse<String> response = worlds.send("POST", "/decks/" + copy.deck() + "/update-plan:apply", account, ApplyWorlds.body(UUID.randomUUID(), version, true), null);
            problem(response, 404, "RESOURCE_NOT_FOUND");
            assertThat(response.body()).doesNotContain(source.deck().toString(), version);
        }
        // the owner's own ordinary deck, the source of another, and a deck nobody ever made
        for (UUID deck : List.of(source.deck(), UUID.randomUUID())) {
            problem(worlds.send("POST", "/decks/" + deck + "/update-plan:apply", source.author(), ApplyWorlds.body(UUID.randomUUID(), version, true), null), 404, "RESOURCE_NOT_FOUND");
        }
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);
        // a stranger cannot replay the owner's command either: the id is the owner's
        UUID command = UUID.randomUUID();
        assertThat(apply.apply(copy, command, version, true).statusCode()).isEqualTo(200);
        String after = apply.snapshot(copy.deck());
        HttpResponse<String> theft = worlds.send("POST", "/decks/" + copy.deck() + "/update-plan:apply", stranger, ApplyWorlds.body(command, version, true), null);
        problem(theft, 404, "RESOURCE_NOT_FOUND");
        assertThat(theft.body()).doesNotContain("deckRevisionId", "APPLIED");
        assertThat(apply.snapshot(copy.deck())).isEqualTo(after);
    }

    @Test
    void aPreparingCopyADetachedCopyAndASourceNobodyCanReadAnyMoreAreSpecificConflictsThatWriteNothing() {
        // preparing: the net of the database is lifted for the test the way the fixtures lift it
        Source source = worlds.source("LINK", 3, 0);
        Copy preparing = copyWithAChange(source);
        String version = apply.version(preparing);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = NULL WHERE deck_id = :d").param("d", preparing.deck()).update();
        });
        String snapshot = apply.snapshot(preparing.deck());
        HttpResponse<String> notReady = apply.apply(preparing, UUID.randomUUID(), version, true);
        problem(notReady, 409, "DECK_PREPARING");
        asInTheContract(notReady, "preparing");
        assertThat(apply.snapshot(preparing.deck())).isEqualTo(snapshot);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = clock_timestamp() WHERE deck_id = :d").param("d", preparing.deck()).update();
        });

        // an answer given before the copy was detached is still the answer to that command
        UUID command = UUID.randomUUID();
        assertThat(apply.apply(preparing, command, version, true).statusCode()).isEqualTo(200);
        assertThat(worlds.send("POST", "/decks/" + preparing.deck() + "/fork:detach", preparing.owner(), null, null).statusCode()).isEqualTo(200);
        HttpResponse<String> replay = apply.apply(preparing, command, version, true);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("idempotency-replayed")).contains("true");
        // ... but a new command on a detached copy is refused by name
        snapshot = apply.snapshot(preparing.deck());
        HttpResponse<String> detached = apply.apply(preparing, UUID.randomUUID(), version, true);
        problem(detached, 409, "FORK_DETACHED");
        asInTheContract(detached, "detached");
        assertThat(apply.snapshot(preparing.deck())).isEqualTo(snapshot);

        // the source went private
        Source privateSource = worlds.source("LINK", 3, 0);
        Copy copy = copyWithAChange(privateSource);
        String privateVersion = apply.version(copy);
        worlds.level(privateSource.author(), privateSource.deck(), "PRIVATE");
        snapshot = apply.snapshot(copy.deck());
        HttpResponse<String> gone = apply.apply(copy, UUID.randomUUID(), privateVersion, true);
        problem(gone, 409, "UPDATE_SOURCE_UNAVAILABLE");
        asInTheContract(gone, "sourceUnavailable");
        assertThat(gone.body()).as("nothing about the source").doesNotContain(privateSource.deck().toString(), privateSource.code());
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);

        // its code was rotated away by lowering the level
        Source rotated = worlds.source("PUBLIC", 3, 0);
        Copy rotatedCopy = worlds.copy(rotated);
        worlds.editText(rotated.author(), rotated.deck(), rotated.materials().get(0).member(), "автор");
        worlds.publish(rotated.author(), rotated.deck(), "PUBLIC");
        String rotatedVersion = apply.version(rotatedCopy);
        worlds.level(rotated.author(), rotated.deck(), "LINK");
        problem(apply.apply(rotatedCopy, UUID.randomUUID(), rotatedVersion, true), 409, "UPDATE_SOURCE_UNAVAILABLE");

        // hidden by moderation, and deleted
        Source hiddenSource = worlds.source("LINK", 3, 0);
        Copy hidden = copyWithAChange(hiddenSource);
        String hiddenVersion = apply.version(hidden);
        Boolean didHide = new TransactionTemplate(transactions).execute(status -> moderation.hide(hiddenSource.deck(), Instant.now()));
        assertThat(didHide).isTrue();
        problem(apply.apply(hidden, UUID.randomUUID(), hiddenVersion, true), 409, "UPDATE_SOURCE_UNAVAILABLE");
        Source deletedSource = worlds.source("LINK", 3, 0);
        Copy deleted = copyWithAChange(deletedSource);
        String deletedVersion = apply.version(deleted);
        assertThat(worlds.send("DELETE", "/decks/" + deletedSource.deck(), deletedSource.author(), null,
                "\"" + decks.read(deletedSource.author(), deletedSource.deck()).path("rowVersion").stringValue(null) + "\"").statusCode()).isEqualTo(204);
        problem(apply.apply(deleted, UUID.randomUUID(), deletedVersion, true), 409, "UPDATE_SOURCE_UNAVAILABLE");
    }
}
