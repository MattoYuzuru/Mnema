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
import app.mnema.learning.update.plan.PlanWorlds.Appended;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442): every row of the classification table of community decks architecture section 7 (and the rows it omits), built with the real services. A source is
 * published, a real copy is made by the production route and materialized by the real job, then the author and the learner each change their deck, the author publishes again, and
 * the plan of the copy is asked of the real service. Materials, exercises, the dependency of an author's exercise on a material the copy kept, deleted, changed or lacks,
 * the node check against a non-head material revision, the objectives, and the pins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanTruthTableIntegrationTest extends PostgresIntegrationTest {
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

    private static String material(Material material) { return UnitKind.MATERIAL.unitId(material.member()); }

    private static String exercise(Authored exercise) { return UnitKind.EXERCISE.unitId(exercise.exerciseId()); }

    private static String objective(Authored exercise) { return UnitKind.OBJECTIVE.unitId(exercise.objectiveId()); }

    private static void expect(Map<String, PlanUnit> units, String id, UnitClass unitClass, Reason reason) {
        assertThat(units).as(id).containsKey(id);
        assertThat(units.get(id).unitClass()).as(id + " class").isEqualTo(unitClass);
        assertThat(units.get(id).reason()).as(id + " reason").isEqualTo(reason);
        assertThat(units.get(id).defaultChoice()).as(id + " default").isEqualTo(switch (unitClass) {
            case NEW, SAFE -> Choice.ACCEPT;
            case CONFLICT, ASK_AGAIN -> Choice.KEEP_MINE;
            case DELETED -> Choice.KEEP;
        });
    }

    @Test
    void everyRowOfTheClassificationTableOfMaterialsExercisesObjectivesAndTheirDependencies() {
        Source source = worlds.source("LINK", 13, 3);
        List<Material> m = source.materials();
        List<Authored> e = source.exercises();
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        UUID learner = copy.owner();
        UUID mine = copy.deck();
        UUID baseBefore = worlds.base(mine);

        // ---- the author, after the copy was made ----
        Authored e0 = worlds.revise(author, deck, m.get(0), e.get(0), "Автор: вопрос 0", "Автор: цель 0");
        worlds.editText(author, deck, m.get(0).member(), "author zero");
        worlds.editText(author, deck, m.get(1).member(), "author one");
        Authored e1 = worlds.revise(author, deck, m.get(1), e.get(1), "Автор: вопрос 1", "Автор: цель 1");
        worlds.deleteExercise(author, deck, e.get(2).exerciseId());
        worlds.editText(author, deck, m.get(2).member(), "same text");
        worlds.editText(author, deck, m.get(3).member(), "author three");
        Authored ex6 = worlds.exercise(worlds.head(author, deck, m.get(3)), "Шестое", null, "Цель 6");
        worlds.deleteMaterial(author, deck, m.get(4).member());
        worlds.deleteMaterial(author, deck, m.get(5).member());
        worlds.deleteMaterial(author, deck, m.get(6).member());
        Authored ex5 = worlds.exercise(worlds.head(author, deck, m.get(9)), "Пятое", null, "Цель 5");
        Authored ex7 = worlds.exercise(worlds.head(author, deck, m.get(10)), "Седьмое", m.get(10).node(), "Цель 7");
        worlds.editText(author, deck, m.get(10).member(), "author ten");
        Authored ex8 = worlds.exercise(worlds.head(author, deck, m.get(11)), "Восьмое", m.get(11).node(), "Цель 8");
        worlds.editSecond(author, deck, m.get(11).member(), "author eleven");
        Appended appended = worlds.append(author, deck, m.get(12).member(), "новый абзац");
        Authored ex9 = worlds.exercise(worlds.head(author, deck, m.get(12)), "Девятое", appended.node(), "Цель 9");
        Material added = worlds.study.addMaterial(author, deck, "новое слово", "перевод");
        Authored ex10 = worlds.exercise(added, "Десятое", null, "Цель 10");
        worlds.publish(author, deck, "LINK");

        // ---- the learner, in the copy ----
        worlds.editText(learner, mine, m.get(1).member(), "learner one");
        worlds.revise(learner, mine, m.get(1), e.get(1), "Ученик: вопрос 1", "Ученик: цель 1");
        worlds.editText(learner, mine, m.get(2).member(), "same text");
        worlds.deleteMaterial(learner, mine, m.get(3).member());
        worlds.deleteMaterial(learner, mine, m.get(5).member());
        worlds.editText(learner, mine, m.get(6).member(), "learner six");
        worlds.editText(learner, mine, m.get(7).member(), "learner seven");
        worlds.editSecond(learner, mine, m.get(10).member(), "learner ten");
        worlds.dropFirst(learner, mine, m.get(11).member());

        UpdatePlan plan = service.plan(learner, mine);

        assertThat(plan.state()).isEqualTo(UpdatePlan.State.READY);
        assertThat(plan.forkDeckId()).isEqualTo(mine);
        assertThat(plan.baseRevisionId()).isEqualTo(baseBefore);
        assertThat(plan.upstreamRevisionId()).isEqualTo(worlds.published(deck));
        assertThat(plan.forkHeadRevisionId()).isEqualTo(worlds.headRevisionOf(mine));
        assertThat(plan.planVersion()).matches("sha256:[0-9a-f]{64}");
        Map<String, PlanUnit> units = byId(plan);

        // materials
        expect(units, material(m.get(0)), UnitClass.SAFE, null);
        expect(units, material(m.get(1)), UnitClass.CONFLICT, Reason.BOTH_CHANGED);
        expect(units, material(m.get(2)), UnitClass.SAFE, null);
        expect(units, material(m.get(3)), UnitClass.CONFLICT, Reason.DELETED_BY_YOU);
        expect(units, material(m.get(4)), UnitClass.DELETED, null);
        expect(units, material(m.get(6)), UnitClass.DELETED, null);
        expect(units, material(m.get(10)), UnitClass.CONFLICT, Reason.BOTH_CHANGED);
        expect(units, material(m.get(11)), UnitClass.CONFLICT, Reason.BOTH_CHANGED);
        expect(units, material(m.get(12)), UnitClass.SAFE, null);
        expect(units, material(added), UnitClass.NEW, null);
        // not listed: deleted by both, changed only by the learner, untouched
        assertThat(units).doesNotContainKeys(material(m.get(5)), material(m.get(7)), material(m.get(8)), material(m.get(9)));
        assertThat(units.get(material(m.get(3))).mineRevisionId()).as("the learner deleted it").isNull();
        assertThat(units.get(material(m.get(4))).authorRevisionId()).as("the author removed it").isNull();
        assertThat(units.get(material(m.get(0))).mineRevisionId()).isEqualTo(m.get(0).itemRevision());
        assertThat(units.get(material(m.get(0))).baseRevisionId()).isEqualTo(m.get(0).itemRevision());

        // exercises
        expect(units, exercise(e0), UnitClass.SAFE, null);
        expect(units, exercise(e1), UnitClass.CONFLICT, Reason.BOTH_CHANGED);
        expect(units, exercise(e.get(2)), UnitClass.DELETED, null);
        expect(units, exercise(ex5), UnitClass.NEW, null);
        expect(units, exercise(ex6), UnitClass.CONFLICT, Reason.MATERIAL_DELETED);
        expect(units, exercise(ex7), UnitClass.NEW, null);
        expect(units, exercise(ex8), UnitClass.CONFLICT, Reason.MATERIAL_CHANGED);
        expect(units, exercise(ex9), UnitClass.NEW, null);
        expect(units, exercise(ex10), UnitClass.NEW, null);
        assertThat(units.get(exercise(ex5)).authorRevisionId()).isEqualTo(ex5.revisionId());
        assertThat(units.get(exercise(ex5)).mineRevisionId()).isNull();
        assertThat(units.get(exercise(e0)).authorRevisionId()).isEqualTo(e0.revisionId());
        assertThat(units.get(exercise(e0)).mineRevisionId()).isEqualTo(e.get(0).revisionId());

        // dependencies: a new material comes with its exercise, a safe fix brings the quoted node, a deleted one does not
        assertThat(units.get(exercise(ex10)).dependsOn()).containsExactlyInAnyOrder(material(added), objective(ex10));
        assertThat(units.get(exercise(ex9)).dependsOn()).containsExactlyInAnyOrder(material(m.get(12)), objective(ex9));
        assertThat(units.get(exercise(ex5)).dependsOn()).containsExactly(objective(ex5));
        assertThat(units.get(exercise(ex7)).dependsOn()).as("the learner's head still has the quoted node").containsExactly(objective(ex7));
        assertThat(units.get(exercise(ex8)).dependsOn()).containsExactly(objective(ex8));
        assertThat(units.get(exercise(ex6)).dependsOn()).containsExactly(objective(ex6));

        // objectives: the author renamed one nobody else touched, both renamed another, every new exercise brings its own
        expect(units, objective(e0), UnitClass.SAFE, null);
        expect(units, objective(e1), UnitClass.CONFLICT, Reason.BOTH_CHANGED);
        for (Authored created : List.of(ex5, ex6, ex7, ex8, ex9, ex10)) expect(units, objective(created), UnitClass.SAFE, null);
        assertThat(units.get(objective(e0)).authorRevisionId()).isEqualTo(e0.objectiveRevisionId());
        assertThat(units.get(objective(e0)).mineRevisionId()).isEqualTo(e.get(0).objectiveRevisionId());
        assertThat(units.get(exercise(e0)).dependsOn()).containsExactly(objective(e0));

        // the deck's own text and the order did not change
        assertThat(units).doesNotContainKeys(UnitKind.METADATA.unitId(mine), UnitKind.ORDER.unitId(mine));

        // the order of the plan: class order, then kind, then the author's manifest order
        assertThat(plan.units()).extracting(unit -> unit.unitClass().ordinal()).isSorted();
        assertThat(plan.summary()).isEqualTo(new UpdatePlan.Summary(1, 4, 11, 8, 3, 0));
        assertThat(plan.units()).hasSize(27);
    }
}
