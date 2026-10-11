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
import app.mnema.learning.update.CombinedPublication;
import app.mnema.learning.update.CombinedUnits;
import app.mnema.learning.update.CombinedUnits.ItemOp;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442), the rows of the classification table that depend on the copy's recorded decisions and on the deck-wide units: a declined change is hidden and asked again when the
 * author changes the unit after it (ASK_AGAIN), an identical outcome is safe, a unit left for later (PENDING) keeps its own old base after the base moved (the base rule, with a real
 * apply of the Updates/2 engine), the title and the order of materials, and an objective the learner kept. The decision rows are written by the test as Updates/4 will write them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanDecisionsIntegrationTest extends PostgresIntegrationTest {
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
    private PlanWorlds worlds;

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
    }

    private static Map<String, PlanUnit> byId(UpdatePlan plan) {
        Map<String, PlanUnit> result = new LinkedHashMap<>();
        plan.units().forEach(unit -> result.put(unit.unitId(), unit));
        return result;
    }

    private static String material(UUID member) { return UnitKind.MATERIAL.unitId(member); }

    private UpdatePlan plan(Copy copy) { return service.plan(copy.owner(), copy.deck()); }

    @Test
    void aDeclinedChangeIsHiddenUntilTheAuthorChangesTheUnitAgainAndThenAskedAgain() {
        Source source = worlds.source("LINK", 5, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();

        UUID a1 = worlds.editText(author, deck, m.get(0).member(), "one");
        Material added = worlds.study.addMaterial(author, deck, "новое", "слово");
        worlds.deleteMaterial(author, deck, m.get(2).member());
        worlds.publish(author, deck, "LINK");
        UpdatePlan first = plan(copy);
        Map<String, PlanUnit> units = byId(first);
        assertThat(units.get(material(m.get(0).member())).unitClass()).isEqualTo(UnitClass.SAFE);
        assertThat(units.get(material(added.member())).unitClass()).isEqualTo(UnitClass.NEW);
        assertThat(units.get(material(m.get(2).member())).unitClass()).isEqualTo(UnitClass.DELETED);

        // the learner declines all three: a fix, a new material and the removal of one he keeps
        worlds.decide(copy.deck(), "MATERIAL", m.get(0).member(), "KEEP_MINE", a1, false, null, false);
        worlds.decide(copy.deck(), "MATERIAL", added.member(), "DECLINE_NEW", added.itemRevision(), false, null, false);
        worlds.decide(copy.deck(), "MATERIAL", m.get(2).member(), "KEEP_MINE", null, true, null, false);
        UpdatePlan decided = plan(copy);
        assertThat(decided.state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
        assertThat(decided.units()).isEmpty();
        assertThat(decided.planVersion()).as("a decision is a part of the plan's pins").isNotEqualTo(first.planVersion());
        assertThat(decided.forkHeadRevisionId()).isEqualTo(first.forkHeadRevisionId());
        assertThat(decided.upstreamRevisionId()).isEqualTo(first.upstreamRevisionId());

        // the author publishes something else: nothing of the declined is asked again
        worlds.editText(author, deck, m.get(4).member(), "four");
        worlds.publish(author, deck, "LINK");
        UpdatePlan elsewhere = plan(copy);
        assertThat(byId(elsewhere).keySet()).containsExactly(material(m.get(4).member()));

        // the author changes the declined units again: the removed one cannot come back, the other two are asked again
        UUID a2 = worlds.editText(author, deck, m.get(0).member(), "one again");
        UUID n2 = worlds.editText(author, deck, added.member(), "новое снова");
        worlds.publish(author, deck, "LINK");
        Map<String, PlanUnit> again = byId(plan(copy));
        assertThat(again.keySet()).containsExactlyInAnyOrder(material(m.get(0).member()), material(added.member()), material(m.get(4).member()));
        PlanUnit fix = again.get(material(m.get(0).member()));
        assertThat(fix.unitClass()).isEqualTo(UnitClass.ASK_AGAIN);
        assertThat(fix.reason()).isEqualTo(Reason.CHANGED_AFTER_DECISION);
        assertThat(fix.defaultChoice()).isEqualTo(Choice.KEEP_MINE);
        assertThat(fix.authorRevisionId()).isEqualTo(a2);
        assertThat(fix.baseRevisionId()).as("judged from the revision the decision covered").isEqualTo(a1);
        PlanUnit revived = again.get(material(added.member()));
        assertThat(revived.unitClass()).isEqualTo(UnitClass.ASK_AGAIN);
        assertThat(revived.authorRevisionId()).isEqualTo(n2);
        assertThat(revived.mineRevisionId()).isNull();
        assertThat(again.get(material(m.get(4).member())).unitClass()).isEqualTo(UnitClass.SAFE);
        assertThat(plan(copy).summary().askAgain()).isEqualTo(2);
        // and ASK_AGAIN comes after every other class in the plan
        assertThat(plan(copy).units()).extracting(PlanUnit::unitClass).containsExactly(UnitClass.SAFE, UnitClass.ASK_AGAIN, UnitClass.ASK_AGAIN);
    }

    @Test
    void aUnitTheLearnerKeptDeletedIsHiddenAndAskedAgainWhenTheAuthorChangesItAgain() {
        Source source = worlds.source("LINK", 3, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        worlds.deleteMaterial(copy.owner(), copy.deck(), m.get(1).member());
        UUID a1 = worlds.editText(source.author(), source.deck(), m.get(1).member(), "author one");
        worlds.publish(source.author(), source.deck(), "LINK");
        PlanUnit conflict = byId(plan(copy)).get(material(m.get(1).member()));
        assertThat(conflict.unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(conflict.reason()).isEqualTo(Reason.DELETED_BY_YOU);

        worlds.decide(copy.deck(), "MATERIAL", m.get(1).member(), "KEEP_DELETED", a1, false, null, false);
        assertThat(plan(copy).units()).isEmpty();
        UUID a2 = worlds.editText(source.author(), source.deck(), m.get(1).member(), "author two");
        worlds.publish(source.author(), source.deck(), "LINK");
        PlanUnit again = byId(plan(copy)).get(material(m.get(1).member()));
        assertThat(again.unitClass()).isEqualTo(UnitClass.ASK_AGAIN);
        assertThat(again.authorRevisionId()).isEqualTo(a2);
        assertThat(again.mineRevisionId()).isNull();
    }

    @Test
    void whenTheAuthorsNewEditEqualsTheLearnersContentTheUnitIsSafeNotAskedAgain() {
        Source source = worlds.source("LINK", 2, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        worlds.editText(copy.owner(), copy.deck(), m.get(0).member(), "learner text");
        UUID a1 = worlds.editText(source.author(), source.deck(), m.get(0).member(), "author text");
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(byId(plan(copy)).get(material(m.get(0).member())).unitClass()).isEqualTo(UnitClass.CONFLICT);
        worlds.decide(copy.deck(), "MATERIAL", m.get(0).member(), "KEEP_MINE", a1, false, null, false);
        assertThat(plan(copy).units()).isEmpty();

        worlds.editText(source.author(), source.deck(), m.get(0).member(), "learner text");
        worlds.publish(source.author(), source.deck(), "LINK");
        PlanUnit same = byId(plan(copy)).get(material(m.get(0).member()));
        assertThat(same.unitClass()).as("the same content: nothing to ask").isEqualTo(UnitClass.SAFE);
        assertThat(same.reason()).isNull();
    }

    @Test
    void aUnitLeftPendingKeepsItsOwnBaseWhenTheBaseMovesAndTheOthersAreNotOfferedTwice() {
        Source source = worlds.source("LINK", 6, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID oldBase = worlds.base(copy.deck());
        UUID a3 = worlds.editText(author, deck, m.get(3).member(), "three");
        UUID a4 = worlds.editText(author, deck, m.get(4).member(), "four");
        worlds.publish(author, deck, "LINK");
        UpdatePlan first = plan(copy);
        assertThat(byId(first).keySet()).containsExactlyInAnyOrder(material(m.get(3).member()), material(m.get(4).member()));
        UUID p1 = first.upstreamRevisionId();

        // the apply, for real: the learner accepts the fix of material 4 through the combined publication, leaves material 3 for later, and the base moves to P1
        int ordinal = items.read(copy.owner(), copy.deck(), m.get(4).member(), null).path("ordinal").intValue();
        publication.publish(new CombinedUnits(UUID.randomUUID(), copy.deck(), copy.owner(), worlds.headRevisionOf(copy.deck()), null,
                List.of(new ItemOp.AdoptReplace(m.get(4).member(), ordinal, a4)), List.of(), List.of()));
        worlds.moveBase(copy.deck(), p1);
        worlds.decide(copy.deck(), "MATERIAL", m.get(3).member(), "PENDING", a3, false, m.get(3).itemRevision(), false);

        UpdatePlan pending = plan(copy);
        assertThat(pending.baseRevisionId()).isEqualTo(p1);
        assertThat(pending.state()).isEqualTo(UpdatePlan.State.READY);
        assertThat(byId(pending).keySet()).as("the accepted fix is not offered again").containsExactly(material(m.get(3).member()));
        PlanUnit left = byId(pending).get(material(m.get(3).member()));
        assertThat(left.unitClass()).isEqualTo(UnitClass.SAFE);
        assertThat(left.baseRevisionId()).as("the unit's own old base, not the moved one").isEqualTo(m.get(3).itemRevision());
        assertThat(left.authorRevisionId()).isEqualTo(a3);
        assertThat(left.mineRevisionId()).isEqualTo(m.get(3).itemRevision());

        // the author changes material 5 and the pending material 3 again: the pending unit is judged against its old base, the new one against the moved base
        UUID a3Again = worlds.editText(author, deck, m.get(3).member(), "three again");
        worlds.editText(author, deck, m.get(5).member(), "five");
        worlds.publish(author, deck, "LINK");
        Map<String, PlanUnit> later = byId(plan(copy));
        assertThat(later.keySet()).containsExactlyInAnyOrder(material(m.get(3).member()), material(m.get(5).member()));
        assertThat(later.get(material(m.get(3).member())).authorRevisionId()).isEqualTo(a3Again);
        assertThat(later.get(material(m.get(3).member())).baseRevisionId()).isEqualTo(m.get(3).itemRevision());
        assertThat(later.get(material(m.get(3).member())).unitClass()).isEqualTo(UnitClass.SAFE);
        assertThat(later.get(material(m.get(5).member())).baseRevisionId()).isEqualTo(m.get(5).itemRevision());

        // the learner edits the pending unit himself: against ITS base he changed it, the author too: a conflict (it would have been missed by a base that moved)
        worlds.editText(copy.owner(), copy.deck(), m.get(3).member(), "learner three");
        assertThat(byId(plan(copy)).get(material(m.get(3).member())).unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(oldBase).isNotEqualTo(p1);
    }

    @Test
    void whenEverythingWasAcceptedAndTheBaseMovedTheCopyIsUpToDate() {
        Source source = worlds.source("LINK", 3, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        UUID a0 = worlds.editText(source.author(), source.deck(), m.get(0).member(), "zero");
        worlds.publish(source.author(), source.deck(), "LINK");
        UpdatePlan plan = plan(copy);
        assertThat(plan.units()).hasSize(1);
        int ordinal = items.read(copy.owner(), copy.deck(), m.get(0).member(), null).path("ordinal").intValue();
        publication.publish(new CombinedUnits(UUID.randomUUID(), copy.deck(), copy.owner(), worlds.headRevisionOf(copy.deck()), null,
                List.of(new ItemOp.AdoptReplace(m.get(0).member(), ordinal, a0)), List.of(), List.of()));
        worlds.moveBase(copy.deck(), plan.upstreamRevisionId());
        UpdatePlan done = plan(copy);
        assertThat(done.state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);
        assertThat(done.units()).isEmpty();
        assertThat(done.summary()).isEqualTo(new UpdatePlan.Summary(0, 0, 0, 0, 0, 0));
        assertThat(done.baseRevisionId()).isEqualTo(done.upstreamRevisionId());
    }

    @Test
    void theTitleAndTheOrderOfMaterialsAreThreeWayUnitsOfTheirOwn() {
        // the author retitles the deck and moves a material; the learner did neither: both are safe
        Source safe = worlds.source("LINK", 4, 0);
        Copy safeCopy = worlds.copy(safe);
        worlds.retitle(safe.author(), safe.deck(), "Второе издание", "Новое описание");
        worlds.reorder(safe.author(), safe.deck(), safe.materials().get(3).member(), 0);
        worlds.publish(safe.author(), safe.deck(), "LINK");
        Map<String, PlanUnit> units = byId(plan(safeCopy));
        PlanUnit metadata = units.get(UnitKind.METADATA.unitId(safeCopy.deck()));
        PlanUnit order = units.get(UnitKind.ORDER.unitId(safeCopy.deck()));
        assertThat(metadata.unitClass()).isEqualTo(UnitClass.SAFE);
        assertThat(order.unitClass()).isEqualTo(UnitClass.SAFE);
        assertThat(metadata.authorRevisionId()).isEqualTo(worlds.published(safe.deck()));
        assertThat(metadata.mineRevisionId()).isEqualTo(worlds.headRevisionOf(safeCopy.deck()));
        assertThat(metadata.baseRevisionId()).isEqualTo(worlds.base(safeCopy.deck()));
        assertThat(units.keySet()).as("a moved material is an order unit, not a material unit").doesNotContain(material(safe.materials().get(3).member()));
        assertThat(plan(safeCopy).units()).extracting(PlanUnit::kind).containsExactly(UnitKind.METADATA, UnitKind.ORDER);

        // the learner retitled the deck and reordered too: both are conflicts
        Source both = worlds.source("LINK", 4, 0);
        Copy bothCopy = worlds.copy(both);
        worlds.retitle(both.author(), both.deck(), "Автор", "Описание автора");
        worlds.reorder(both.author(), both.deck(), both.materials().get(3).member(), 0);
        worlds.publish(both.author(), both.deck(), "LINK");
        worlds.retitle(bothCopy.owner(), bothCopy.deck(), "Ученик", "Описание ученика");
        worlds.reorder(bothCopy.owner(), bothCopy.deck(), both.materials().get(1).member(), 3);
        Map<String, PlanUnit> conflicts = byId(plan(bothCopy));
        assertThat(conflicts.get(UnitKind.METADATA.unitId(bothCopy.deck())).unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(conflicts.get(UnitKind.METADATA.unitId(bothCopy.deck())).reason()).isEqualTo(Reason.BOTH_CHANGED);
        assertThat(conflicts.get(UnitKind.ORDER.unitId(bothCopy.deck())).unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(conflicts.get(UnitKind.ORDER.unitId(bothCopy.deck())).defaultChoice()).isEqualTo(Choice.KEEP_MINE);

        // the learner retitled to the very same text: nothing to decide for the title
        Source same = worlds.source("LINK", 3, 0);
        Copy sameCopy = worlds.copy(same);
        worlds.retitle(same.author(), same.deck(), "Общее", "Общее описание");
        worlds.publish(same.author(), same.deck(), "LINK");
        worlds.retitle(sameCopy.owner(), sameCopy.deck(), "Общее", "Общее описание");
        assertThat(plan(sameCopy).state()).isEqualTo(UpdatePlan.State.UP_TO_DATE);

        // only the learner reordered: the author's plan has no order unit at all
        Source mineOnly = worlds.source("LINK", 4, 0);
        Copy mineCopy = worlds.copy(mineOnly);
        worlds.reorder(mineCopy.owner(), mineCopy.deck(), mineOnly.materials().get(0).member(), 3);
        worlds.editText(mineOnly.author(), mineOnly.deck(), mineOnly.materials().get(2).member(), "two");
        worlds.publish(mineOnly.author(), mineOnly.deck(), "LINK");
        assertThat(plan(mineCopy).units()).extracting(PlanUnit::kind).containsExactly(UnitKind.MATERIAL);
    }

    @Test
    void aRecordedDecisionOnTheTitleAndTheOrderHidesThemUntilTheAuthorChangesThemAgain() {
        Source source = worlds.source("LINK", 4, 0);
        Copy copy = worlds.copy(source);
        worlds.retitle(source.author(), source.deck(), "Первое издание", "Описание");
        worlds.reorder(source.author(), source.deck(), source.materials().get(3).member(), 0);
        worlds.publish(source.author(), source.deck(), "LINK");
        UUID p1 = plan(copy).upstreamRevisionId();
        worlds.decide(copy.deck(), "METADATA", copy.deck(), "KEEP_MINE", p1, false, null, false);
        worlds.decide(copy.deck(), "ORDER", copy.deck(), "KEEP_MINE", p1, false, null, false);
        assertThat(plan(copy).units()).isEmpty();

        // a publication that changes neither: still hidden
        worlds.editText(source.author(), source.deck(), source.materials().get(1).member(), "one");
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(plan(copy).units()).extracting(PlanUnit::kind).containsExactly(UnitKind.MATERIAL);

        // the author retitles and moves again
        worlds.retitle(source.author(), source.deck(), "Второе издание", "Описание");
        worlds.reorder(source.author(), source.deck(), source.materials().get(2).member(), 0);
        worlds.publish(source.author(), source.deck(), "LINK");
        Map<String, PlanUnit> again = byId(plan(copy));
        assertThat(again.get(UnitKind.METADATA.unitId(copy.deck())).unitClass()).isEqualTo(UnitClass.ASK_AGAIN);
        assertThat(again.get(UnitKind.ORDER.unitId(copy.deck())).unitClass()).isEqualTo(UnitClass.ASK_AGAIN);
        assertThat(again.get(UnitKind.ORDER.unitId(copy.deck())).baseRevisionId()).isEqualTo(p1);
    }

    @Test
    void anObjectiveTheLearnerKeptHidesItAndMakesTheExercisesThatNeedItConflicts() {
        Source source = worlds.source("LINK", 2, 1);
        Authored exercise = source.exercises().getFirst();
        Copy copy = worlds.copy(source);
        Authored revised = worlds.revise(source.author(), source.deck(), source.materials().get(0), exercise, "Новый вопрос", "Новая цель");
        worlds.publish(source.author(), source.deck(), "LINK");
        Map<String, PlanUnit> units = byId(plan(copy));
        assertThat(units.get(UnitKind.EXERCISE.unitId(exercise.exerciseId())).unitClass()).isEqualTo(UnitClass.SAFE);
        assertThat(units.get(UnitKind.OBJECTIVE.unitId(exercise.objectiveId())).unitClass()).isEqualTo(UnitClass.SAFE);

        worlds.decide(copy.deck(), "OBJECTIVE", exercise.objectiveId(), "KEEP_MINE", revised.objectiveRevisionId(), false, null, false);
        Map<String, PlanUnit> kept = byId(plan(copy));
        assertThat(kept).doesNotContainKey(UnitKind.OBJECTIVE.unitId(exercise.objectiveId()));
        PlanUnit dependent = kept.get(UnitKind.EXERCISE.unitId(exercise.exerciseId()));
        assertThat(dependent.unitClass()).as("the exercise needs the objective revision the learner declined").isEqualTo(UnitClass.CONFLICT);
        assertThat(dependent.reason()).isEqualTo(Reason.OBJECTIVE_RENAMED);

        // the author renames the objective once more: it is asked again, with its exercise
        Authored again = worlds.revise(source.author(), source.deck(), source.materials().get(0), revised, "Новый вопрос", "Ещё одна цель");
        worlds.publish(source.author(), source.deck(), "LINK");
        PlanUnit asked = byId(plan(copy)).get(UnitKind.OBJECTIVE.unitId(exercise.objectiveId()));
        assertThat(asked.unitClass()).isEqualTo(UnitClass.ASK_AGAIN);
        assertThat(asked.authorRevisionId()).isEqualTo(again.objectiveRevisionId());
    }

    @Test
    void aRevisionOfTheAuthorThatWasTakenDownIsNeverOfferedAndATakenDownRevisionOfTheCopyShowsNoText() {
        Source source = worlds.source("LINK", 5, 1);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        app.mnema.learning.support.TakedownFixture takedown = new app.mnema.learning.support.TakedownFixture(jdbc);

        UUID a1 = worlds.editText(author, deck, m.get(1).member(), "снят");
        Authored revised = worlds.revise(author, deck, m.get(0), source.exercises().get(0), "Снятый вопрос", null);
        UUID a2 = worlds.editText(author, deck, m.get(2).member(), "цитата");
        Authored quoting = worlds.exercise(worlds.head(author, deck, m.get(2)), "Вопрос с цитатой", m.get(2).node(), "Цель цитаты");
        worlds.editText(author, deck, m.get(4).member(), "обычная правка");
        worlds.deleteMaterial(author, deck, m.get(3).member());
        worlds.publish(author, deck, "LINK");
        assertThat(byId(plan(copy)).keySet()).contains(material(m.get(1).member()), UnitKind.EXERCISE.unitId(source.exercises().get(0).exerciseId()),
                material(m.get(2).member()), UnitKind.EXERCISE.unitId(quoting.exerciseId()), material(m.get(3).member()));

        takedown.item(deck, m.get(1).member(), a1);
        takedown.exercise(deck, source.exercises().get(0).exerciseId(), revised.revisionId());
        takedown.item(deck, m.get(2).member(), a2);
        takedown.item(deck, m.get(3).member(), m.get(3).itemRevision());

        UpdatePlan plan = plan(copy);
        Map<String, PlanUnit> units = byId(plan);
        assertThat(units).as("a taken-down material revision, a taken-down exercise revision and an exercise that quotes a taken-down material are not on offer")
                .doesNotContainKeys(material(m.get(1).member()), UnitKind.EXERCISE.unitId(source.exercises().get(0).exerciseId()), material(m.get(2).member()),
                        UnitKind.EXERCISE.unitId(quoting.exerciseId()));
        assertThat(units.get(material(m.get(4).member())).unitClass()).isEqualTo(UnitClass.SAFE);
        // the removal of a material the copy still holds at a taken-down revision is listed, without the text of that revision
        assertThat(units.get(material(m.get(3).member())).unitClass()).isEqualTo(UnitClass.DELETED);
        assertThat(service.page(copy.owner(), copy.deck(), null, "100").path("units").valueStream()
                .filter(unit -> unit.path("unitId").stringValue("").equals(material(m.get(3).member()))).findFirst().orElseThrow().path("title").stringValue(null)).isEmpty();
        assertThat(service.unit(copy.owner(), copy.deck(), UnitKind.MATERIAL, m.get(3).member(), plan.planVersion()).path("mine").path("unavailable").booleanValue(false)).isTrue();
        assertThat(service.unit(copy.owner(), copy.deck(), UnitKind.MATERIAL, m.get(3).member(), plan.planVersion()).path("mine").has("document")).isFalse();
    }

    @Test
    void whenAnExerciseHasTwoReasonsTheWorseOneIsTheReason() {
        Source source = worlds.source("LINK", 4, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        worlds.deleteMaterial(copy.owner(), copy.deck(), m.get(0).member());
        worlds.dropFirst(copy.owner(), copy.deck(), m.get(1).member());
        Authored onDeleted = worlds.exercise(worlds.head(author, deck, m.get(0)), "Про удалённый", null, "Цель 0");
        Authored onChanged = worlds.exercise(worlds.head(author, deck, m.get(1)), "Про изменённый", m.get(1).node(), "Цель 1");
        Authored onUntouched = worlds.exercise(worlds.head(author, deck, m.get(2)), "Про нетронутый", null, "Цель 2");
        Material first = worlds.head(author, deck, m.get(0));
        Material second = worlds.head(author, deck, m.get(1));
        // one exercise on a material the learner deleted that also quotes a material whose node he dropped: two reasons at once
        Authored onBoth = Authored.of(worlds.study.publish(first, worlds.study.freeResponse(first,
                app.mnema.learning.support.StudyFixtures.blocks(app.mnema.learning.support.StudyFixtures.text("Про оба"),
                        app.mnema.learning.support.StudyFixtures.quote(second, m.get(1).node())), app.mnema.learning.support.StudyFixtures.blocks(), "memory"), "Цель оба"));
        worlds.publish(author, deck, "LINK");
        Map<String, PlanUnit> units = byId(plan(copy));
        assertThat(units.get(UnitKind.EXERCISE.unitId(onDeleted.exerciseId())).reason()).isEqualTo(Reason.MATERIAL_DELETED);
        assertThat(units.get(UnitKind.EXERCISE.unitId(onChanged.exerciseId())).reason()).isEqualTo(Reason.MATERIAL_CHANGED);
        assertThat(units.get(UnitKind.EXERCISE.unitId(onUntouched.exerciseId())).unitClass()).isEqualTo(UnitClass.NEW);
        assertThat(units.get(UnitKind.EXERCISE.unitId(onBoth.exerciseId())).reason()).as("two material reasons: a deleted material is the worse").isEqualTo(Reason.MATERIAL_DELETED);

        // the learner also declined every objective: the third reason is present everywhere and is the least
        for (Authored exercise : List.of(onDeleted, onChanged, onUntouched, onBoth)) {
            worlds.decide(copy.deck(), "OBJECTIVE", exercise.objectiveId(), "KEEP_MINE", exercise.objectiveRevisionId(), false, null, false);
        }
        Map<String, PlanUnit> declined = byId(plan(copy));
        assertThat(declined.get(UnitKind.EXERCISE.unitId(onDeleted.exerciseId())).reason()).isEqualTo(Reason.MATERIAL_DELETED);
        assertThat(declined.get(UnitKind.EXERCISE.unitId(onChanged.exerciseId())).reason()).isEqualTo(Reason.MATERIAL_CHANGED);
        assertThat(declined.get(UnitKind.EXERCISE.unitId(onBoth.exerciseId())).reason()).isEqualTo(Reason.MATERIAL_DELETED);
        PlanUnit alone = declined.get(UnitKind.EXERCISE.unitId(onUntouched.exerciseId()));
        assertThat(alone.unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(alone.reason()).isEqualTo(Reason.OBJECTIVE_RENAMED);
    }

    @Test
    void anExerciseWhoseOnlyChangeIsEnabledIsAChangeAndEnabledIsPartOfItsContent() {
        Source source = worlds.source("LINK", 5, 5);
        List<Material> m = source.materials();
        List<Authored> e = source.exercises();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        // exercise 0: only the author disables it
        Authored disabled = worlds.reviseOriginal(author, deck, m.get(0), e.get(0), 0, false);
        // exercise 1: both disable it, the content is otherwise untouched: the same content
        worlds.reviseOriginal(author, deck, m.get(1), e.get(1), 1, false);
        worlds.reviseOriginal(copy.owner(), copy.deck(), m.get(1), e.get(1), 1, false);
        // exercise 2: the author disables it, the learner rewrites the question
        worlds.reviseOriginal(author, deck, m.get(2), e.get(2), 2, false);
        worlds.revise(copy.owner(), copy.deck(), m.get(2), e.get(2), "Вопрос ученика", null);
        // exercise 3: both write the same new question, the author disables it and the learner does not: they differ by `enabled` alone
        worlds.revise(author, deck, m.get(3), e.get(3), "Общий вопрос", null, false);
        worlds.revise(copy.owner(), copy.deck(), m.get(3), e.get(3), "Общий вопрос", null, true);
        // exercise 4: only the learner disables it
        worlds.reviseOriginal(copy.owner(), copy.deck(), m.get(4), e.get(4), 4, false);
        worlds.publish(author, deck, "LINK");
        UpdatePlan plan = plan(copy);
        Map<String, PlanUnit> units = byId(plan);
        assertThat(units.get(UnitKind.EXERCISE.unitId(e.get(0).exerciseId())).unitClass()).as("a change of `enabled` alone is a change").isEqualTo(UnitClass.SAFE);
        assertThat(units.get(UnitKind.EXERCISE.unitId(e.get(0).exerciseId())).authorRevisionId()).isEqualTo(disabled.revisionId());
        assertThat(units.get(UnitKind.EXERCISE.unitId(e.get(1).exerciseId())).unitClass()).as("both disabled it: the same content").isEqualTo(UnitClass.SAFE);
        assertThat(units.get(UnitKind.EXERCISE.unitId(e.get(2).exerciseId())).unitClass()).isEqualTo(UnitClass.CONFLICT);
        PlanUnit differsByEnabled = units.get(UnitKind.EXERCISE.unitId(e.get(3).exerciseId()));
        assertThat(differsByEnabled.unitClass()).as("`enabled` is part of the content compared").isEqualTo(UnitClass.CONFLICT);
        assertThat(differsByEnabled.reason()).isEqualTo(Reason.BOTH_CHANGED);
        assertThat(units).doesNotContainKey(UnitKind.EXERCISE.unitId(e.get(4).exerciseId()));
        JsonNode side = service.unit(copy.owner(), copy.deck(), UnitKind.EXERCISE, e.get(0).exerciseId(), plan.planVersion());
        assertThat(side.path("author").path("enabled").booleanValue(true)).isFalse();
        assertThat(side.path("mine").path("enabled").booleanValue(false)).isTrue();
    }

    @Test
    void anExerciseTheLearnerDeletedIsAConflictOfItsOwnKind() {
        Source source = worlds.source("LINK", 4, 3);
        List<Material> m = source.materials();
        List<Authored> e = source.exercises();
        Copy copy = worlds.copy(source);
        worlds.revise(source.author(), source.deck(), m.get(0), e.get(0), "Автор поправил", null);
        worlds.deleteExercise(copy.owner(), copy.deck(), e.get(0).exerciseId());
        // the author did not touch exercise 1: nothing to say; both removed exercise 2: nothing to say
        worlds.deleteExercise(copy.owner(), copy.deck(), e.get(1).exerciseId());
        worlds.deleteExercise(source.author(), source.deck(), e.get(2).exerciseId());
        worlds.deleteExercise(copy.owner(), copy.deck(), e.get(2).exerciseId());
        worlds.publish(source.author(), source.deck(), "LINK");
        Map<String, PlanUnit> units = byId(plan(copy));
        PlanUnit gone = units.get(UnitKind.EXERCISE.unitId(e.get(0).exerciseId()));
        assertThat(gone.unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(gone.reason()).isEqualTo(Reason.DELETED_BY_YOU);
        assertThat(gone.defaultChoice()).isEqualTo(Choice.KEEP_MINE);
        assertThat(gone.mineRevisionId()).isNull();
        assertThat(gone.baseRevisionId()).isEqualTo(e.get(0).revisionId());
        assertThat(units).doesNotContainKeys(UnitKind.EXERCISE.unitId(e.get(1).exerciseId()), UnitKind.EXERCISE.unitId(e.get(2).exerciseId()));
    }

    @Test
    void aSafeExerciseThatNeedsAnObjectiveTheLearnerRenamedIsAConflict() {
        Source source = worlds.source("LINK", 3, 1);
        Material material = source.materials().get(0);
        Authored first = source.exercises().get(0);
        // two exercises share ONE objective; the copy is made after both were published
        Authored second = worlds.exerciseReusing(worlds.head(source.author(), source.deck(), material), first, "Второе упражнение на ту же цель");
        assertThat(second.objectiveId()).isEqualTo(first.objectiveId());
        worlds.publish(source.author(), source.deck(), "LINK");
        Copy copy = worlds.copy(source);

        // the author rewrites the first exercise and renames the objective; the learner renames the same objective through the SECOND exercise and leaves the first alone
        Authored authors = worlds.revise(source.author(), source.deck(), material, first, "Поправленный вопрос", "Цель автора");
        worlds.revise(copy.owner(), copy.deck(), material, second, "Вопрос ученика", "Цель ученика");
        worlds.publish(source.author(), source.deck(), "LINK");
        Map<String, PlanUnit> units = byId(plan(copy));
        PlanUnit objective = units.get(UnitKind.OBJECTIVE.unitId(first.objectiveId()));
        assertThat(objective.unitClass()).as("his head is not an ancestor of the author's revision").isEqualTo(UnitClass.CONFLICT);
        assertThat(objective.authorRevisionId()).isEqualTo(authors.objectiveRevisionId());
        PlanUnit exercise = units.get(UnitKind.EXERCISE.unitId(first.exerciseId()));
        assertThat(exercise.unitClass()).as("the learner never touched this exercise, but it needs the objective he renamed").isEqualTo(UnitClass.CONFLICT);
        assertThat(exercise.reason()).isEqualTo(Reason.OBJECTIVE_RENAMED);
        assertThat(exercise.dependsOn()).contains(UnitKind.OBJECTIVE.unitId(first.objectiveId()));
        assertThat(units).as("the second exercise is the learner's alone").doesNotContainKey(UnitKind.EXERCISE.unitId(second.exerciseId()));
    }
}
