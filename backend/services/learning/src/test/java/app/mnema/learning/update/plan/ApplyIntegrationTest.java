package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.update.CombinedPublication;
import app.mnema.learning.update.CombinedUnits;
import app.mnema.learning.update.FinalWrites;
import app.mnema.learning.update.apply.ApplyCommand;
import app.mnema.learning.update.apply.ApplyService;
import app.mnema.learning.update.plan.ApplyWorlds.Pick;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.json;
import static app.mnema.learning.update.plan.PlanWorlds.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Updates/4 (#443) through the real application: a real source with its author, a real copy made by {@code POST /decks/forks} and materialized by the real job, the author's and the
 * learner's edits through the editor's services, and the apply command over its real route {@code POST /decks/{id}/update-plan:apply}. The epic's acceptance criteria are the tests:
 * «accept all safe» leaves what the learner changed alone, repeating a command changes nothing more, a stale plan writes nothing, a declined change returns only when the author edits
 * the unit again, an author's deletion applies only on request. Plus the dependencies, a partial apply and the pending units after it, the order and the title.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class ApplyIntegrationTest extends PostgresIntegrationTest {
    private static final JsonNode CONTRACT = PlanWorlds.updatesContract();

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
    @Autowired private UpdatePlanService service;
    @Autowired private CombinedPublication publication;
    @Autowired private ApplyService applies;
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

    private static String material(Material material) { return UnitKind.MATERIAL.unitId(material.member()); }

    private static String material(UUID member) { return UnitKind.MATERIAL.unitId(member); }

    private static String exercise(Authored exercise) { return UnitKind.EXERCISE.unitId(exercise.exerciseId()); }

    private static String objective(Authored exercise) { return UnitKind.OBJECTIVE.unitId(exercise.objectiveId()); }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).path("code").stringValue("")).isEqualTo(code);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
    }

    private static java.util.Set<String> names(JsonNode node) {
        java.util.Set<String> result = new java.util.TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    /** The problem has the members, and the type, title, status, detail and code, of the contract's example. */
    private static void asInTheContract(HttpResponse<String> response, String name) {
        JsonNode expected = CONTRACT.path("problems").path(name).path("body");
        assertThat(response.statusCode()).as(name).isEqualTo(CONTRACT.path("problems").path(name).path("status").intValue());
        JsonNode actual = json(response);
        assertThat(names(actual)).as(name).isEqualTo(names(expected));
        for (String field : new String[] {"type", "title", "status", "detail", "code"}) assertThat(actual.path(field)).as(name + "." + field).isEqualTo(expected.path(field));
        assertThat(actual.path("instance").stringValue(null)).endsWith("/update-plan:apply");
    }

    private static JsonNode unit(JsonNode plan, String unitId) {
        for (JsonNode unit : plan.path("units")) if (unitId.equals(unit.path("unitId").stringValue(null))) return unit;
        throw new AssertionError("no unit " + unitId + " in " + plan);
    }

    private static Map<String, String> expected(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) result.put(pairs[at], pairs[at + 1]);
        return result;
    }

    // ---- the acceptance criteria ----

    @Test
    void acceptAllSafeTakesTheFixesAndTheNewUnitsAndLeavesWhatTheLearnerChangedOrTheAuthorRemovedAndRepeatingTheCommandChangesNothing() {
        Source source = worlds.source("LINK", 8, 3);
        List<Material> m = source.materials();
        List<Authored> e = source.exercises();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID learner = copy.owner();
        UUID mine = copy.deck();
        UUID baseBefore = worlds.base(mine);

        UUID a0 = worlds.editText(author, deck, m.get(0).member(), "author zero");
        UUID a1 = worlds.editText(author, deck, m.get(1).member(), "author one");
        worlds.deleteMaterial(author, deck, m.get(4).member());
        Material added = worlds.study.addMaterial(author, deck, "новое слово", "перевод");
        Authored created = worlds.exercise(added, "Новый вопрос", null, "Новая цель");
        Authored revised = worlds.revise(author, deck, m.get(2), e.get(2), "Вопрос автора", "Цель автора");
        worlds.retitle(author, deck, "Новое название", "Новое описание");
        worlds.publish(author, deck, "LINK");
        UUID published = worlds.published(deck);
        UUID learnerOne = worlds.editText(learner, mine, m.get(1).member(), "learner one");

        UpdatePlan plan = apply.plan(copy);
        assertThat(ApplyWorlds.classes(plan)).isEqualTo(expected(
                UnitKind.METADATA.unitId(mine), "SAFE", material(m.get(0)), "SAFE", material(added), "NEW", exercise(created), "NEW", exercise(e.get(2)), "SAFE",
                objective(created), "SAFE", objective(e.get(2)), "SAFE", material(m.get(1)), "CONFLICT", material(m.get(4)), "DELETED"));
        long revisionsBefore = apply.revisions(mine);
        String untouchedBeforeSource = apply.snapshot(deck);

        UUID command = UUID.randomUUID();
        HttpResponse<String> response = apply.apply(copy, command, plan.planVersion(), true);
        JsonNode answer = ok(response);
        assertThat(response.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(response.headers().firstValue("idempotency-replayed")).isEmpty();
        assertThat(answer.path("commandId").stringValue(null)).isEqualTo(command.toString());
        assertThat(answer.path("state").stringValue(null)).isEqualTo("APPLIED");
        assertThat(answer.path("applied").intValue()).isEqualTo(7);
        assertThat(answer.path("declined").intValue()).isZero();
        assertThat(answer.path("pending").intValue()).isEqualTo(2);
        assertThat(answer.path("alsoAccepted").intValue()).isZero();
        assertThat(answer.path("baseRevisionId").stringValue(null)).isEqualTo(published.toString());
        assertThat(answer.path("deckRevisionId").stringValue(null)).isEqualTo(worlds.headRevisionOf(mine).toString());
        assertThat(apply.revisions(mine)).as("one new revision for the whole command").isEqualTo(revisionsBefore + 1);
        assertThat(names(answer)).as("the answer of the contract").isEqualTo(names(CONTRACT.path("apply").path("applied").path("body")));

        // what was taken is the author's, what the learner changed or the author removed is as it was
        assertThat(worlds.itemHead(mine, m.get(0).member())).isEqualTo(a0);
        assertThat(worlds.itemHead(mine, m.get(1).member())).as("a conflict stays the learner's").isEqualTo(learnerOne).isNotEqualTo(a1);
        assertThat(worlds.itemHead(mine, m.get(4).member())).as("a removal by the author is not applied").isEqualTo(m.get(4).itemRevision());
        assertThat(worlds.itemHead(mine, added.member())).isEqualTo(added.itemRevision());
        assertThat(apply.exerciseHead(mine, created.exerciseId())).isEqualTo(created.revisionId());
        assertThat(apply.exerciseHead(mine, e.get(2).exerciseId())).isEqualTo(revised.revisionId());
        assertThat(apply.objectiveHead(mine, created.objectiveId())).isEqualTo(created.objectiveRevisionId());
        assertThat(apply.objectiveHead(mine, e.get(2).objectiveId())).isEqualTo(revised.objectiveRevisionId());
        assertThat(apply.title(mine)).isEqualTo("Новое название");
        assertThat(worlds.base(mine)).as("the base moves to the published revision").isEqualTo(published).isNotEqualTo(baseBefore);
        assertThat(apply.decisions(mine)).as("the units left for later keep their own old base and the author's state offered").isEqualTo(expected(
                "MATERIAL:" + m.get(1).member(), "PENDING " + a1 + " " + m.get(1).itemRevision(),
                "MATERIAL:" + m.get(4).member(), "PENDING - " + m.get(4).itemRevision()));
        assertThat(apply.snapshot(deck)).as("the source is not touched").isEqualTo(untouchedBeforeSource);

        // the same command again: the stored answer, flagged, and nothing more is written
        long receipts = apply.receipts(learner);
        String snapshot = apply.snapshot(mine);
        HttpResponse<String> again = apply.apply(copy, command, plan.planVersion(), true);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(json(again)).isEqualTo(answer);
        assertThat(again.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(apply.revisions(mine)).isEqualTo(revisionsBefore + 1);
        assertThat(apply.receipts(learner)).isEqualTo(receipts);
        assertThat(apply.snapshot(mine)).isEqualTo(snapshot);
        // the same id with another body is a conflict
        problem(apply.apply(copy, command, plan.planVersion(), false), 409, "IDEMPOTENCY_CONFLICT");
        HttpResponse<String> conflict = apply.apply(copy, command, plan.planVersion(), true, Pick.material(m.get(1).member(), "ACCEPT"));
        problem(conflict, 409, "IDEMPOTENCY_CONFLICT");
        asInTheContract(conflict, "idempotencyConflict");
        assertThat(apply.snapshot(mine)).isEqualTo(snapshot);

        // the plan after it lists exactly what was left
        JsonNode next = apply.planRoute(copy);
        assertThat(next.path("state").stringValue(null)).isEqualTo("READY");
        assertThat(ApplyWorlds.classes(next)).isEqualTo(expected(material(m.get(1)), "CONFLICT", material(m.get(4)), "DELETED"));
        assertThat(next.path("planVersion").stringValue(null)).isNotEqualTo(plan.planVersion());
        assertThat(next.has("lastApplyFailed")).isFalse();
        // and the old plan version is stale now, also for a command that was never sent
        HttpResponse<String> stale = apply.apply(copy, UUID.randomUUID(), plan.planVersion(), true);
        problem(stale, 409, "UPDATE_PLAN_STALE");
        asInTheContract(stale, "stale");
    }

    @Test
    void aStalePlanIsAConflictAndWritesNothing() {
        Source source = worlds.source("LINK", 6, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        worlds.editText(source.author(), source.deck(), m.get(0).member(), "author zero");
        worlds.publish(source.author(), source.deck(), "LINK");
        String read = apply.version(copy);

        // the copy was edited after the plan was read
        worlds.editText(copy.owner(), copy.deck(), m.get(3).member(), "learner three");
        String snapshot = apply.snapshot(copy.deck());
        problem(apply.apply(copy, UUID.randomUUID(), read, true), 409, "UPDATE_PLAN_STALE");
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);

        // the author republished after the plan was read
        String second = apply.version(copy);
        worlds.editText(source.author(), source.deck(), m.get(1).member(), "author one");
        worlds.publish(source.author(), source.deck(), "LINK");
        snapshot = apply.snapshot(copy.deck());
        problem(apply.apply(copy, UUID.randomUUID(), second, true), 409, "UPDATE_PLAN_STALE");
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);

        // a plan version nobody ever issued is as stale as an old one
        problem(apply.apply(copy, UUID.randomUUID(), "sha256:" + "0".repeat(64), true), 409, "UPDATE_PLAN_STALE");
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);

        // the fresh one goes through
        ok(apply.apply(copy, true));
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
    }

    @Test
    void aDeclinedChangeIsNotOfferedAgainUntilTheAuthorEditsThatUnitAgainAndThenItIsAskedAgainAndNeverTakenByAcceptAllSafe() {
        Source source = worlds.source("LINK", 6, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID a0 = worlds.editText(author, deck, m.get(0).member(), "author zero");
        UUID a1 = worlds.editText(author, deck, m.get(1).member(), "author one");
        worlds.publish(author, deck, "LINK");

        JsonNode first = ok(apply.apply(copy, false, Pick.material(m.get(0).member(), "DECLINE"), Pick.material(m.get(1).member(), "ACCEPT")));
        assertThat(first.path("applied").intValue()).isEqualTo(1);
        assertThat(first.path("declined").intValue()).isEqualTo(1);
        assertThat(first.path("pending").intValue()).isZero();
        assertThat(worlds.itemHead(copy.deck(), m.get(0).member())).as("declined: still the base").isEqualTo(m.get(0).itemRevision());
        assertThat(worlds.itemHead(copy.deck(), m.get(1).member())).isEqualTo(a1);
        assertThat(apply.decisions(copy.deck())).isEqualTo(expected("MATERIAL:" + m.get(0).member(), "KEEP_MINE " + a0 + " ."));
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
        assertThat(apply.plan(copy).units()).isEmpty();

        // the author publishes something else: the declined unit is not asked again
        worlds.editText(author, deck, m.get(4).member(), "author four");
        worlds.publish(author, deck, "LINK");
        assertThat(ApplyWorlds.classes(apply.plan(copy))).isEqualTo(expected(material(m.get(4)), "SAFE"));
        ok(apply.apply(copy, true));
        assertThat(apply.decisions(copy.deck())).isEqualTo(expected("MATERIAL:" + m.get(0).member(), "KEEP_MINE " + a0 + " ."));

        // the author edits the declined unit again: now it is asked again, with the reason, and "accept all safe" leaves it
        UUID a0b = worlds.editText(author, deck, m.get(0).member(), "author zero again");
        worlds.publish(author, deck, "LINK");
        JsonNode asked = apply.planRoute(copy);
        assertThat(ApplyWorlds.classes(asked)).isEqualTo(expected(material(m.get(0)), "ASK_AGAIN"));
        assertThat(asked.path("units").get(0).path("reason").stringValue(null)).isEqualTo("CHANGED_AFTER_DECISION");
        assertThat(asked.path("units").get(0).path("authorRevisionId").stringValue(null)).isEqualTo(a0b.toString());
        JsonNode untouched = ok(apply.apply(copy, true));
        assertThat(untouched.path("applied").intValue()).isZero();
        assertThat(untouched.path("pending").intValue()).isEqualTo(1);
        assertThat(untouched.path("deckRevisionId").isNull()).as("nothing was adopted: no revision").isTrue();
        assertThat(worlds.itemHead(copy.deck(), m.get(0).member())).isEqualTo(m.get(0).itemRevision());
        // left for later it stays on offer as a pending unit judged from its own old base (the learner's version differs from it: a conflict, keep mine by default), still not taken by "accept all safe"
        JsonNode pending = apply.planRoute(copy);
        assertThat(pending.path("units")).hasSize(1);
        assertThat(pending.path("units").get(0).path("unitId").stringValue(null)).isEqualTo(material(m.get(0)));
        assertThat(pending.path("units").get(0).path("class").stringValue(null)).isEqualTo("CONFLICT");
        assertThat(pending.path("units").get(0).path("defaultChoice").stringValue(null)).isEqualTo("KEEP_MINE");
        ok(apply.apply(copy, true));
        assertThat(worlds.itemHead(copy.deck(), m.get(0).member())).isEqualTo(m.get(0).itemRevision());
        // taken by name, it is taken and its decision is gone
        ok(apply.apply(copy, false, Pick.material(m.get(0).member(), "ACCEPT")));
        assertThat(worlds.itemHead(copy.deck(), m.get(0).member())).isEqualTo(a0b);
        assertThat(apply.decisions(copy.deck())).isEmpty();
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
    }

    @Test
    void anAuthorsDeletionAppliesOnlyWithDeleteAndKeepingItIsRemembered() {
        Source source = worlds.source("LINK", 6, 2);
        List<Material> m = source.materials();
        List<Authored> e = source.exercises();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        worlds.deleteMaterial(author, deck, m.get(4).member());
        worlds.deleteMaterial(author, deck, m.get(5).member());
        worlds.deleteExercise(author, deck, e.get(1).exerciseId());
        worlds.deleteExercise(author, deck, e.get(0).exerciseId());
        worlds.deleteMaterial(author, deck, m.get(0).member());
        worlds.publish(author, deck, "LINK");
        assertThat(ApplyWorlds.classes(apply.plan(copy))).isEqualTo(expected(
                material(m.get(0)), "DELETED", material(m.get(4)), "DELETED", material(m.get(5)), "DELETED", exercise(e.get(0)), "DELETED", exercise(e.get(1)), "DELETED"));

        // "accept all safe" removes nothing
        JsonNode safe = ok(apply.apply(copy, true));
        assertThat(safe.path("applied").intValue()).isZero();
        assertThat(safe.path("pending").intValue()).isEqualTo(5);
        for (Material material : m) assertThat(worlds.itemHead(copy.deck(), material.member())).isEqualTo(material.itemRevision());
        for (Authored exercise : e) assertThat(apply.exerciseHead(copy.deck(), exercise.exerciseId())).isEqualTo(exercise.revisionId());

        // a material the copy's exercises are about cannot go while they stay: refused by name (the exercise is the author's removal in the plan, kept by name here), nothing is written
        String snapshot = apply.snapshot(copy.deck());
        HttpResponse<String> used = apply.apply(copy, false, Pick.material(m.get(0).member(), "DELETE"), Pick.exercise(e.get(0).exerciseId(), "KEEP"));
        problem(used, 400, "INVALID_REQUEST");
        assertThat(json(used).path("reason").stringValue(null)).isEqualTo("material_still_used");
        assertThat(json(used).path("unitId").stringValue(null)).isEqualTo(material(m.get(0)));
        assertThat(json(used).path("dependency").stringValue(null)).isEqualTo(exercise(e.get(0)));
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);

        // the others, by name: one removed, one kept (and the choices that are not for a removal are refused by name)
        HttpResponse<String> wrong = apply.apply(copy, false, Pick.material(m.get(4).member(), "ACCEPT"));
        problem(wrong, 400, "INVALID_REQUEST");
        assertThat(json(wrong).path("reason").stringValue(null)).isEqualTo("choice_not_allowed");
        assertThat(json(wrong).path("unitId").stringValue(null)).isEqualTo(material(m.get(4)));
        asInTheContract(wrong, "invalidChoice");
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);
        JsonNode done = ok(apply.apply(copy, false, Pick.material(m.get(4).member(), "DELETE"), Pick.material(m.get(5).member(), "KEEP"),
                Pick.exercise(e.get(0).exerciseId(), "DELETE"), Pick.exercise(e.get(1).exerciseId(), "KEEP"), Pick.material(m.get(0).member(), "DELETE")));
        assertThat(done.path("applied").intValue()).isEqualTo(3);
        assertThat(done.path("declined").intValue()).isEqualTo(2);
        assertThat(worlds.itemHead(copy.deck(), m.get(4).member())).isNull();
        assertThat(worlds.itemHead(copy.deck(), m.get(0).member())).isNull();
        assertThat(apply.exerciseHead(copy.deck(), e.get(0).exerciseId())).isNull();
        assertThat(worlds.itemHead(copy.deck(), m.get(5).member())).isEqualTo(m.get(5).itemRevision());
        assertThat(apply.exerciseHead(copy.deck(), e.get(1).exerciseId())).isEqualTo(e.get(1).revisionId());
        assertThat(apply.members(copy.deck())).doesNotContain(m.get(4).member(), m.get(0).member()).contains(m.get(5).member());
        // what is kept is remembered as "the author had removed it" and is not offered again
        assertThat(apply.decisions(copy.deck())).isEqualTo(expected("EXERCISE:" + e.get(1).exerciseId(), "KEEP_MINE - .", "MATERIAL:" + m.get(5).member(), "KEEP_MINE - ."));
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
    }

    @Test
    void deletingAMaterialTakesTheRemovalOfTheExercisesAboutItAndALearnersOwnExerciseKeepsItRefusedByName() {
        Source source = worlds.source("LINK", 4, 1);
        List<Material> m = source.materials();
        Authored e0 = source.exercises().get(0);
        Copy copy = worlds.copy(source);
        // the learner makes an exercise of his own about material 1
        Authored own = worlds.exercise(worlds.head(copy.owner(), copy.deck(), m.get(1)), "Свой вопрос", null, "Своя цель");
        worlds.deleteExercise(source.author(), source.deck(), e0.exerciseId());
        worlds.deleteMaterial(source.author(), source.deck(), m.get(0).member());
        worlds.deleteMaterial(source.author(), source.deck(), m.get(1).member());
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(ApplyWorlds.classes(apply.plan(copy))).isEqualTo(expected(material(m.get(0)), "DELETED", material(m.get(1)), "DELETED", exercise(e0), "DELETED"));

        // material 1: the learner's own exercise is not in the plan, so it cannot be removed with the material
        String snapshot = apply.snapshot(copy.deck());
        HttpResponse<String> refused = apply.apply(copy, false, Pick.material(m.get(1).member(), "DELETE"));
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("material_still_used");
        assertThat(json(refused).path("unitId").stringValue(null)).isEqualTo(material(m.get(1)));
        assertThat(json(refused).has("dependency")).isFalse();
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);

        // material 0: the exercise the author removed too goes with it, and is counted
        JsonNode done = ok(apply.apply(copy, false, Pick.material(m.get(0).member(), "DELETE")));
        assertThat(done.path("applied").intValue()).isEqualTo(2);
        assertThat(done.path("alsoAccepted").intValue()).isEqualTo(1);
        assertThat(done.path("pending").intValue()).isEqualTo(1);
        assertThat(worlds.itemHead(copy.deck(), m.get(0).member())).isNull();
        assertThat(apply.exerciseHead(copy.deck(), e0.exerciseId())).isNull();
        assertThat(apply.exerciseHead(copy.deck(), own.exerciseId())).isEqualTo(own.revisionId());
        assertThat(worlds.itemHead(copy.deck(), m.get(1).member())).isEqualTo(m.get(1).itemRevision());
    }

    @Test
    void anObjectiveMovesOnlyWithTheExercisesThatEvidenceItSoDecliningTheExerciseLeavesTheObjectiveWaiting() {
        Source source = worlds.source("LINK", 4, 2);
        List<Material> m = source.materials();
        Authored e0 = source.exercises().get(0);
        Copy declining = worlds.copy(source);
        Copy byName = worlds.copy(source);
        Copy refusing = worlds.copy(source);
        // the author renames the objective of the first exercise (a new revision of the exercise, which pins the renamed objective)
        Authored renamed = worlds.revise(source.author(), source.deck(), m.get(0), e0, "Вопрос автора", "Новое название цели");
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(ApplyWorlds.classes(apply.plan(declining))).isEqualTo(expected(exercise(e0), "SAFE", objective(e0), "SAFE"));

        // «accept all safe» but not the exercise: the objective waits (its head would name a revision no exercise of the copy pins), and the answer is 200
        JsonNode answer = ok(apply.apply(declining, true, Pick.exercise(e0.exerciseId(), "DECLINE")));
        assertThat(answer.path("applied").intValue()).isZero();
        assertThat(answer.path("declined").intValue()).isEqualTo(1);
        assertThat(answer.path("pending").intValue()).isEqualTo(1);
        assertThat(apply.objectiveHead(declining.deck(), e0.objectiveId())).isEqualTo(e0.objectiveRevisionId());
        assertThat(apply.exerciseHead(declining.deck(), e0.exerciseId())).isEqualTo(e0.revisionId());
        assertThat(apply.decisions(declining.deck())).isEqualTo(expected(
                "EXERCISE:" + e0.exerciseId(), "KEEP_MINE " + renamed.revisionId() + " .", "OBJECTIVE:" + e0.objectiveId(), "PENDING " + renamed.objectiveRevisionId() + " -"));

        // the objective taken by name brings the exercise that evidences it
        JsonNode pulled = ok(apply.apply(byName, false, Pick.of(UnitKind.OBJECTIVE, e0.objectiveId(), "ACCEPT")));
        assertThat(pulled.path("applied").intValue()).isEqualTo(2);
        assertThat(pulled.path("alsoAccepted").intValue()).isEqualTo(1);
        assertThat(apply.objectiveHead(byName.deck(), e0.objectiveId())).isEqualTo(renamed.objectiveRevisionId());
        assertThat(apply.exerciseHead(byName.deck(), e0.exerciseId())).isEqualTo(renamed.revisionId());

        // ... unless that exercise was declined by name
        String snapshot = apply.snapshot(refusing.deck());
        HttpResponse<String> refused = apply.apply(refusing, false, Pick.of(UnitKind.OBJECTIVE, e0.objectiveId(), "ACCEPT"), Pick.exercise(e0.exerciseId(), "DECLINE"));
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("dependency_declined");
        assertThat(json(refused).path("unitId").stringValue(null)).isEqualTo(objective(e0));
        assertThat(json(refused).path("dependency").stringValue(null)).isEqualTo(exercise(e0));
        assertThat(apply.snapshot(refusing.deck())).isEqualTo(snapshot);
    }

    // ---- dependencies ----

    @Test
    void takingAnExerciseTakesItsMaterialAndObjectiveAndAConflictingChoiceIsRefusedByName() {
        Source fresh = worlds.source("LINK", 4, 1);
        Copy first = worlds.copy(fresh);
        Copy second = worlds.copy(fresh);
        Material material = worlds.study.addMaterial(fresh.author(), fresh.deck(), "ещё слово", "перевод");
        Authored exercise = worlds.exercise(material, "Ещё вопрос", null, "Ещё цель");
        worlds.publish(fresh.author(), fresh.deck(), "LINK");
        assertThat(apply.plan(first).units().stream().filter(unit -> unit.unitId().equals(exercise(exercise))).findFirst().orElseThrow().dependsOn())
                .containsExactlyInAnyOrder(material(material), objective(exercise));

        // a declined dependency of a unit that is taken by name: refused, both units are named, nothing is written
        String snapshot = apply.snapshot(second.deck());
        HttpResponse<String> refused = apply.apply(second, false, Pick.exercise(exercise.exerciseId(), "ACCEPT"), Pick.material(material.member(), "DECLINE"));
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("dependency_declined");
        assertThat(json(refused).path("unitId").stringValue(null)).isEqualTo(exercise(exercise));
        assertThat(json(refused).path("dependency").stringValue(null)).isEqualTo(material(material));
        asInTheContract(refused, "dependencyDeclined");
        assertThat(apply.snapshot(second.deck())).isEqualTo(snapshot);

        // named alone, it brings both
        JsonNode taken = ok(apply.apply(first, false, Pick.exercise(exercise.exerciseId(), "ACCEPT")));
        assertThat(taken.path("applied").intValue()).isEqualTo(3);
        assertThat(taken.path("alsoAccepted").intValue()).isEqualTo(2);
        assertThat(taken.path("pending").intValue()).isZero();
        assertThat(worlds.itemHead(first.deck(), material.member())).isEqualTo(material.itemRevision());
        assertThat(apply.exerciseHead(first.deck(), exercise.exerciseId())).isEqualTo(exercise.revisionId());
        assertThat(apply.objectiveHead(first.deck(), exercise.objectiveId())).isEqualTo(exercise.objectiveRevisionId());
        assertThat(apply.plan(first).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);

        // "accept all safe, but not this material": the exercise of that material waits, and its objective waits with it (an objective moves only with the exercises that evidence it);
        // the material is remembered as declined
        JsonNode partly = ok(apply.apply(second, true, Pick.material(material.member(), "DECLINE")));
        assertThat(partly.path("applied").intValue()).isZero();
        assertThat(partly.path("declined").intValue()).isEqualTo(1);
        assertThat(partly.path("pending").intValue()).isEqualTo(2);
        assertThat(apply.objectiveHead(second.deck(), exercise.objectiveId())).isNull();
        assertThat(worlds.itemHead(second.deck(), material.member())).isNull();
        assertThat(apply.exerciseHead(second.deck(), exercise.exerciseId())).isNull();
        assertThat(apply.decisions(second.deck())).containsEntry("MATERIAL:" + material.member(), "DECLINE_NEW " + material.itemRevision() + " .")
                .containsEntry("EXERCISE:" + exercise.exerciseId(), "PENDING " + exercise.revisionId() + " -");
    }

    // ---- a partial apply ----

    @Test
    void aPartialApplyMovesTheBaseAndLeavesPendingUnitsThatTheNextPlanListsExactlyWithTheirOwnBaseAndAuthorsNewChangesJoinThem() {
        Source source = worlds.source("LINK", 6, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID baseBefore = worlds.base(copy.deck());
        UUID a0 = worlds.editText(author, deck, m.get(0).member(), "author zero");
        UUID a1 = worlds.editText(author, deck, m.get(1).member(), "author one");
        UUID a2 = worlds.editText(author, deck, m.get(2).member(), "author two");
        UUID a3 = worlds.editText(author, deck, m.get(3).member(), "author three");
        Material added = worlds.study.addMaterial(author, deck, "новое слово", "перевод");
        worlds.publish(author, deck, "LINK");
        UUID published = worlds.published(deck);

        JsonNode answer = ok(apply.apply(copy, false, Pick.material(m.get(0).member(), "ACCEPT"), Pick.material(m.get(1).member(), "DECLINE")));
        assertThat(answer.path("applied").intValue()).isEqualTo(1);
        assertThat(answer.path("declined").intValue()).isEqualTo(1);
        assertThat(answer.path("pending").intValue()).isEqualTo(3);
        assertThat(worlds.base(copy.deck())).isEqualTo(published).isNotEqualTo(baseBefore);
        assertThat(apply.decisions(copy.deck())).isEqualTo(expected(
                "MATERIAL:" + m.get(1).member(), "KEEP_MINE " + a1 + " .",
                "MATERIAL:" + m.get(2).member(), "PENDING " + a2 + " " + m.get(2).itemRevision(),
                "MATERIAL:" + m.get(3).member(), "PENDING " + a3 + " " + m.get(3).itemRevision(),
                "MATERIAL:" + added.member(), "PENDING " + added.itemRevision() + " -"));
        assertThat(worlds.itemHead(copy.deck(), m.get(0).member())).isEqualTo(a0);
        assertThat(worlds.itemHead(copy.deck(), m.get(2).member())).isEqualTo(m.get(2).itemRevision());

        // the next plan shows exactly the pending units, as they were
        JsonNode next = apply.planRoute(copy);
        assertThat(ApplyWorlds.classes(next)).isEqualTo(expected(material(added), "NEW", material(m.get(2)), "SAFE", material(m.get(3)), "SAFE"));
        assertThat(next.path("baseRevisionId").stringValue(null)).isEqualTo(published.toString());
        assertThat(unit(next, material(m.get(2))).path("authorRevisionId").stringValue(null)).isEqualTo(a2.toString());
        assertThat(unit(next, material(m.get(2))).path("baseRevisionId").stringValue(null)).isEqualTo(m.get(2).itemRevision().toString());

        // the author changes one pending unit again and a new one: both are on offer, the others are as before
        UUID a2b = worlds.editText(author, deck, m.get(2).member(), "author two again");
        UUID a4 = worlds.editText(author, deck, m.get(4).member(), "author four");
        worlds.publish(author, deck, "LINK");
        JsonNode later = apply.planRoute(copy);
        assertThat(ApplyWorlds.classes(later)).isEqualTo(expected(material(added), "NEW", material(m.get(2)), "SAFE", material(m.get(3)), "SAFE", material(m.get(4)), "SAFE"));
        assertThat(unit(later, material(m.get(2))).path("authorRevisionId").stringValue(null)).isEqualTo(a2b.toString());

        // everything safe: the pending rows are gone, only what was declined is remembered, and the base is the new published revision
        ok(apply.apply(copy, true));
        assertThat(worlds.itemHead(copy.deck(), m.get(2).member())).isEqualTo(a2b);
        assertThat(worlds.itemHead(copy.deck(), m.get(3).member())).isEqualTo(a3);
        assertThat(worlds.itemHead(copy.deck(), m.get(4).member())).isEqualTo(a4);
        assertThat(worlds.itemHead(copy.deck(), added.member())).isEqualTo(added.itemRevision());
        assertThat(apply.decisions(copy.deck())).isEqualTo(expected("MATERIAL:" + m.get(1).member(), "KEEP_MINE " + a1 + " ."));
        assertThat(worlds.base(copy.deck())).isEqualTo(worlds.published(deck));
        assertThat(apply.plan(copy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
    }

    // ---- the final writer's own check ----

    private FinalWrites writes(UpdatePlan plan, UUID command, UUID deck, UUID publishedRevision) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString()).put("planVersion", plan.planVersion()).put("baseRevisionId", plan.baseRevisionId().toString())
                .put("publishedRevisionId", publishedRevision.toString()).put("applied", 0).put("declined", 0).put("pending", plan.units().size()).put("alsoAccepted", 0);
        body.putObject("request").put("deckId", deck.toString());
        body.putArray("rows");
        return new FinalWrites("update.apply", body);
    }

    @Test
    void theFinalWriterChecksThePinsAgainOnTheNoOpPathToo() {
        Source source = worlds.source("LINK", 4, 0);
        Copy copy = worlds.copy(source);
        UUID deck = copy.deck();
        worlds.editText(source.author(), source.deck(), source.materials().get(0).member(), "author zero");
        worlds.publish(source.author(), source.deck(), "LINK");
        UpdatePlan plan = apply.plan(copy);
        UUID head = worlds.headRevisionOf(deck);
        UUID command = UUID.randomUUID();
        FinalWrites writes = writes(plan, command, deck, plan.upstreamRevisionId());
        CombinedUnits nothing = new CombinedUnits(command, deck, copy.owner(), head, null, List.of(), List.of(), List.of(), writes);

        // the author republished after the plan was read: the command adopts nothing, so the engine makes no compare-and-set, but the writer sees the other published revision
        worlds.editText(source.author(), source.deck(), source.materials().get(1).member(), "author one");
        worlds.publish(source.author(), source.deck(), "LINK");
        String snapshot = apply.snapshot(deck);
        long receipts = apply.receipts(copy.owner());
        assertThatThrownBy(() -> publication.publish(nothing)).isInstanceOf(VersionConflictException.class);
        assertThat(apply.snapshot(deck)).isEqualTo(snapshot);
        assertThat(apply.receipts(copy.owner())).isEqualTo(receipts);
        // pins that name a revision the source never published are as stale
        UpdatePlan fresh = apply.plan(copy);
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(command, deck, copy.owner(), head, null, List.of(), List.of(), List.of(),
                writes(fresh, command, deck, UUID.randomUUID())))).isInstanceOf(VersionConflictException.class);
        // a plan version that is not the recomputed one
        UpdatePlan other = new UpdatePlan(fresh.state(), fresh.forkDeckId(), fresh.forkHeadRevisionId(), fresh.upstreamRevisionId(), fresh.baseRevisionId(), "sha256:" + "0".repeat(64),
                null, fresh.summary(), fresh.units());
        assertThatThrownBy(() -> publication.publish(new CombinedUnits(command, deck, copy.owner(), head, null, List.of(), List.of(), List.of(),
                writes(other, command, deck, fresh.upstreamRevisionId())))).isInstanceOf(VersionConflictException.class);
        assertThat(apply.snapshot(deck)).isEqualTo(snapshot);

        // the control: the pins of the current plan are written, with no revision
        FinalWrites right = writes(fresh, command, deck, fresh.upstreamRevisionId());
        assertThat(publication.publish(new CombinedUnits(command, deck, copy.owner(), head, null, List.of(), List.of(), List.of(), right)).result().changed()).isFalse();
        assertThat(worlds.base(deck)).isEqualTo(fresh.upstreamRevisionId());
        assertThat(worlds.headRevisionOf(deck)).isEqualTo(head);
        assertThat(apply.receipts(copy.owner())).as("the engine's receipt and the answer's").isEqualTo(receipts + 2);
    }

    @Test
    void theApplyCommandIsNotRunInsideATransactionOfItsCaller() {
        Source source = worlds.source("LINK", 3, 0);
        Copy copy = worlds.copy(source);
        worlds.editText(source.author(), source.deck(), source.materials().get(0).member(), "author zero");
        worlds.publish(source.author(), source.deck(), "LINK");
        ApplyCommand command = ApplyCommand.read(new java.io.ByteArrayInputStream(ApplyWorlds.body(UUID.randomUUID(), apply.version(copy), true).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // the repeatable-read snapshot of the plan could not be opened inside an outer read-committed transaction: the service refuses to be called in one
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> applies.apply(copy.owner(), copy.deck(), command)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(applies.apply(copy.owner(), copy.deck(), command)).isInstanceOf(ApplyService.Applied.class);
    }

    // ---- two requests at once ----

    @Test
    void theSameCommandSentTwiceAtOnceIsAppliedOnceAndTwoDifferentCommandsOnTheSamePlanApplyOneAndTheOtherIsStale() throws Exception {
        Source source = worlds.source("LINK", 6, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        for (int index = 0; index < 4; index++) worlds.editText(source.author(), source.deck(), m.get(index).member(), "author " + index);
        worlds.publish(source.author(), source.deck(), "LINK");
        long revisionsBefore = apply.revisions(copy.deck());
        String version = apply.version(copy);

        UUID command = UUID.randomUUID();
        List<HttpResponse<String>> same = both(() -> apply.apply(copy, command, version, true));
        assertThat(same).extracting(HttpResponse::statusCode).containsExactly(200, 200);
        assertThat(same.stream().filter(response -> response.headers().firstValue("idempotency-replayed").isPresent()).count()).as("one applied, one answered from the receipt").isBetween(0L, 1L);
        assertThat(json(same.get(0))).isEqualTo(json(same.get(1)));
        assertThat(apply.revisions(copy.deck())).isEqualTo(revisionsBefore + 1);
        assertThat(apply.decisions(copy.deck())).isEmpty();

        // two different commands made from one plan: the first to commit wins, the other is stale and wrote nothing
        Source other = worlds.source("LINK", 6, 0);
        Copy racing = worlds.copy(other);
        for (int index = 0; index < 4; index++) worlds.editText(other.author(), other.deck(), other.materials().get(index).member(), "author " + index);
        worlds.publish(other.author(), other.deck(), "LINK");
        long before = apply.revisions(racing.deck());
        String raced = apply.version(racing);
        List<HttpResponse<String>> different = both(() -> apply.apply(racing, UUID.randomUUID(), raced, true));
        assertThat(different).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(different.stream().filter(response -> response.statusCode() == 409).map(ApplyWorlds::code)).containsExactly("UPDATE_PLAN_STALE");
        assertThat(apply.revisions(racing.deck())).isEqualTo(before + 1);
        assertThat(apply.plan(racing).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
        assertThat(worlds.base(racing.deck())).isEqualTo(worlds.published(other.deck()));
    }

    private static List<HttpResponse<String>> both(java.util.concurrent.Callable<HttpResponse<String>> request) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return request.call();
                }));
            }
            go.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (var future : futures) responses.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- order and the title ----

    @Test
    void theOrderAndTheTitleAreTakenWhenOnlyTheAuthorChangedThemAndAConflictWaitsForAChoice() {
        Source source = worlds.source("LINK", 7, 0);
        List<Material> m = source.materials();
        Copy untouched = worlds.copy(source);
        Copy reordered = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        worlds.reorder(author, deck, m.get(6).member(), 0);
        worlds.reorder(author, deck, m.get(2).member(), 5);
        worlds.retitle(author, deck, "Второе издание", "Описание автора");
        worlds.publish(author, deck, "LINK");
        // the learner of the second copy reorders too, and adds a material of his own at the end
        worlds.reorder(reordered.owner(), reordered.deck(), m.get(3).member(), 0);
        Material own = worlds.study.addMaterial(reordered.owner(), reordered.deck(), "своё слово", "перевод");
        List<UUID> authors = apply.publishedMembers(deck);
        assertThat(authors).isNotEqualTo(apply.members(untouched.deck()));

        assertThat(ApplyWorlds.classes(apply.plan(untouched))).isEqualTo(expected(UnitKind.METADATA.unitId(untouched.deck()), "SAFE", UnitKind.ORDER.unitId(untouched.deck()), "SAFE"));
        JsonNode taken = ok(apply.apply(untouched, true));
        assertThat(taken.path("applied").intValue()).isEqualTo(2);
        assertThat(apply.members(untouched.deck())).isEqualTo(authors);
        assertThat(apply.title(untouched.deck())).isEqualTo("Второе издание");
        assertThat(apply.plan(untouched).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);

        // the other: the order is a conflict (he reordered), the title is his too if he changed it; "accept all safe" takes the title only
        Map<String, String> plan = ApplyWorlds.classes(apply.plan(reordered));
        assertThat(plan).containsEntry(UnitKind.ORDER.unitId(reordered.deck()), "CONFLICT").containsEntry(UnitKind.METADATA.unitId(reordered.deck()), "SAFE");
        List<UUID> before = apply.members(reordered.deck());
        ok(apply.apply(reordered, true));
        assertThat(apply.members(reordered.deck())).as("his order is kept").isEqualTo(before);
        assertThat(apply.title(reordered.deck())).isEqualTo("Второе издание");
        // by name the author's order is taken; his own material stays, behind the one it followed
        ok(apply.apply(reordered, false, Pick.of(UnitKind.ORDER, reordered.deck(), "ACCEPT")));
        List<UUID> result = apply.members(reordered.deck());
        assertThat(result.stream().filter(authors::contains).toList()).isEqualTo(authors);
        assertThat(result).contains(own.member());
        assertThat(result.indexOf(own.member())).as("his own addition stays right behind the material it followed").isEqualTo(result.indexOf(m.get(6).member()) + 1);
        assertThat(apply.plan(reordered).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
    }

    // ---- the request ----

    @Test
    void aRequestThatIsNotTheContractsIsRefusedWithoutWritingAnything() {
        Source source = worlds.source("LINK", 4, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        worlds.editText(source.author(), source.deck(), m.get(0).member(), "author zero");
        worlds.publish(source.author(), source.deck(), "LINK");
        String version = apply.version(copy);
        String snapshot = apply.snapshot(copy.deck());
        UUID id = UUID.randomUUID();
        List<String> bodies = new ArrayList<>(List.of(
                "{}", "[]", "null", "not json", "{\"commandId\":\"" + id + "\"}", "{\"planVersion\":\"" + version + "\"}",
                "{\"commandId\":\"" + id + "\",\"planVersion\":\"sha256:short\"}", "{\"commandId\":\"nope\",\"planVersion\":\"" + version + "\"}",
                "{\"commandId\":\"" + id + "\",\"planVersion\":\"" + version + "\",\"acceptSafe\":1}",
                "{\"commandId\":\"" + id + "\",\"planVersion\":\"" + version + "\",\"choices\":\"all\"}",
                "{\"commandId\":\"" + id + "\",\"planVersion\":\"" + version + "\",\"revisionIds\":[]}",
                "{\"commandId\":\"" + id + "\",\"planVersion\":\"" + version + "\",\"choices\":[{\"unitId\":\"MATERIAL:" + m.get(0).member() + "\",\"choice\":\"accept\"}]}",
                "{\"commandId\":\"" + id + "\",\"planVersion\":\"" + version + "\",\"choices\":[{\"unitId\":\"MATERIAL:" + m.get(0).member() + "\",\"choice\":\"ACCEPT\",\"revisionId\":\""
                        + UUID.randomUUID() + "\"}]}",
                ApplyWorlds.body(id, version, false, Pick.material(m.get(0).member(), "ACCEPT"), Pick.material(m.get(0).member(), "ACCEPT")),
                ApplyWorlds.body(id, version, false, Pick.material(m.get(1).member(), "ACCEPT")),
                ApplyWorlds.body(id, version, false, Pick.material(m.get(0).member(), "KEEP_MINE")),
                ApplyWorlds.body(id, version, false, Pick.material(UUID.randomUUID(), "ACCEPT")),
                ApplyWorlds.body(id, version, true, Pick.of(UnitKind.EXERCISE, m.get(0).member(), "ACCEPT"))));
        bodies.add("{\"commandId\":\"" + id + "\",\"planVersion\":\"" + version + "\",\"choices\":[" + ("{\"unitId\":\"ORDER:" + copy.deck() + "\",\"choice\":\"ACCEPT\"},").repeat(1_000)
                + "{\"unitId\":\"ORDER:" + copy.deck() + "\",\"choice\":\"ACCEPT\"}]}");
        for (String body : bodies) {
            HttpResponse<String> response = apply.post(copy, body);
            assertThat(response.statusCode()).as(body.substring(0, Math.min(120, body.length())) + " -> " + response.body()).isEqualTo(400);
            assertThat(ApplyWorlds.code(response)).isEqualTo("INVALID_REQUEST");
        }
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);
        // no token, a token without the write scope, and a body of another type
        assertThat(HttpIdentityFixture.send(port, "POST", "/decks/" + copy.deck() + "/update-plan:apply", null, ApplyWorlds.body(id, version, true), Map.of()).statusCode()).isEqualTo(401);
        problem(HttpIdentityFixture.send(port, "POST", "/decks/" + copy.deck() + "/update-plan:apply", HttpIdentityFixture.token(copy.owner(), "learning.read"),
                ApplyWorlds.body(id, version, true), Map.of()), 403, "ACCESS_DENIED");
        assertThat(apply.snapshot(copy.deck())).isEqualTo(snapshot);
        problem(worlds.send("POST", "/decks/not-an-id/update-plan:apply", copy.owner(), ApplyWorlds.body(id, version, true), null), 400, "INVALID_REQUEST");
    }
}
