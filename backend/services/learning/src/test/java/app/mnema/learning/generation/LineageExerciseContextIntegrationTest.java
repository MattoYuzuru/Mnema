package app.mnema.learning.generation;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/5 (#427): the AI context and admission of a deck read its own exercise and objective heads. A copy's prompt never
 * contains the source's private exercises or objectives, and the source never learns what the copy added.
 */
@SpringBootTest
class LineageExerciseContextIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ContextRepository context;
    @Autowired private GenerationRepository repository;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private StudySessionService studies;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fixtures;
    private SharedScopeFixture shared;

    @BeforeEach
    void fixtures() {
        fixtures = new StudyFixtures(decks, items, exercises, studies, media, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    @Test
    void theContextOfACopyExcludesTheSourcesPrivateExercisesAndObjectives() {
        Material a = fixtures.material();
        JsonNode inherited = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("Inherited question")), blocks(), "memory"),
                "Inherited objective");
        Copy copy = shared.copy(a.deck(), UUID.randomUUID());
        Material ca = new Material(copy.owner(), copy.deckId(), a.member(), a.itemRevision(), a.node(), a.distractor(), a.divider(), a.root());

        // after the fork: the source adds a private exercise (and objective), then revises the inherited objective; the copy adds its own
        JsonNode secret = fixtures.publish(a, fixtures.selfCheck(a, blocks(text("Source secret")), blocks(text("the answer"))),
                "Source-only objective");
        ObjectNode revise = fixtures.createBody(a, fixtures.freeResponse(a, blocks(text("Inherited question")), blocks(), "memory"), "x");
        revise.set("objective", JSON.createObjectNode().put("operation", "revise")
                .put("objectiveId", inherited.path("objectiveId").stringValue(null))
                .put("expectedObjectiveRevisionId", inherited.path("objectiveRevisionId").stringValue(null))
                .put("title", "Source rename"));
        revise.put("expectedExerciseRevisionId", inherited.path("exerciseRevisionId").stringValue(null));
        JsonNode renamed = exercises.publish(a.actor(), a.deck(), UUID.fromString(inherited.path("exerciseId").stringValue(null)),
                fixtures.deckVersion(a), ExerciseCommand.readUpdate(bytes(revise))).acknowledgement();
        JsonNode mine = fixtures.publish(ca, fixtures.freeResponse(ca, blocks(text("Copy question")), blocks(), "mine"), "Copy objective");

        // counts, mechanics, objectives and quoted exercises: the reading deck's heads only
        assertThat(context.exerciseCounts(copy.deckId(), List.of(a.member())).get(a.member())).isEqualTo(2);
        assertThat(context.exerciseCounts(a.deck(), List.of(a.member())).get(a.member())).isEqualTo(2);
        assertThat(context.mechanicCounts(copy.deckId(), List.of(a.member())).get(a.member())).containsOnlyKeys("FREE_RESPONSE");
        assertThat(context.mechanicCounts(a.deck(), List.of(a.member())).get(a.member())).containsOnlyKeys("FREE_RESPONSE", "SELF_CHECK");
        assertThat(context.objectives(copy.deckId(), a.member(), 10)).extracting(ContextRepository.ObjectiveLine::title)
                .containsExactly("Inherited objective", "Copy objective");
        assertThat(context.objectives(a.deck(), a.member(), 10)).extracting(ContextRepository.ObjectiveLine::title)
                .containsExactly("Source rename", "Source-only objective");
        List<String> copyExercises = context.exercises(copy.deckId(), a.member(), 10).stream().map(ContextRepository.ExerciseLine::content).toList();
        assertThat(copyExercises).hasSize(2);
        assertThat(String.join(" ", copyExercises)).contains("Copy question", "Inherited question").doesNotContain("Source secret");
        List<String> sourceExercises = context.exercises(a.deck(), a.member(), 10).stream().map(ContextRepository.ExerciseLine::content).toList();
        assertThat(String.join(" ", sourceExercises)).contains("Source secret").doesNotContain("Copy question");

        // admission: the roster and the objective titles of a deck are its own
        UUID secretExercise = UUID.fromString(secret.path("exerciseId").stringValue(null));
        assertThat(repository.exerciseHeadRevision(copy.owner(), copy.deckId(), secretExercise)).isEmpty();
        assertThat(repository.exerciseHeadRevision(a.actor(), a.deck(), secretExercise)).isPresent();
        UUID secretObjective = UUID.fromString(secret.path("objectiveId").stringValue(null));
        UUID secretObjectiveRevision = UUID.fromString(secret.path("objectiveRevisionId").stringValue(null));
        assertThat(repository.objectiveTitle(copy.owner(), copy.deckId(), secretObjective, secretObjectiveRevision)).isEmpty();
        assertThat(repository.objectiveTitle(a.actor(), a.deck(), secretObjective, secretObjectiveRevision)).contains("Source-only objective");
        UUID inheritedObjective = UUID.fromString(inherited.path("objectiveId").stringValue(null));
        UUID sourceRename = UUID.fromString(renamed.path("objectiveRevisionId").stringValue(null));
        UUID original = UUID.fromString(inherited.path("objectiveRevisionId").stringValue(null));
        assertThat(repository.objectiveTitle(copy.owner(), copy.deckId(), inheritedObjective, original)).contains("Inherited objective");
        assertThat(repository.objectiveTitle(copy.owner(), copy.deckId(), inheritedObjective, sourceRename)).isEmpty();
        assertThat(repository.objectiveTitle(a.actor(), a.deck(), inheritedObjective, sourceRename)).contains("Source rename");
        assertThat(repository.objectiveTitle(a.actor(), a.deck(), inheritedObjective, original)).contains("Inherited objective");
        UUID mineObjective = UUID.fromString(mine.path("objectiveId").stringValue(null));
        UUID mineRevision = UUID.fromString(mine.path("objectiveRevisionId").stringValue(null));
        assertThat(repository.objectiveTitle(copy.owner(), copy.deckId(), mineObjective, mineRevision)).contains("Copy objective");
        assertThat(repository.objectiveTitle(a.actor(), a.deck(), mineObjective, mineRevision)).isEmpty();
    }

    @Test
    void generationAdmissionDoesNotKnowARevisionOnlyPinnedByAnInheritedExercise() {
        var s = app.mnema.learning.support.PinnedRevisionScenario.build(shared, fixtures);
        assertThat(repository.itemRevisionExists(s.copy().owner(), s.copy().deckId(), s.member(), s.pinned())).isFalse();
        assertThat(repository.itemRevisionExists(s.copy().owner(), s.copy().deckId(), s.member(), s.head())).isTrue();
        assertThat(repository.itemRevisionExists(s.copy().owner(), s.copy().deckId(), s.member(), s.later())).isFalse();
        assertThat(repository.itemRevisionExists(s.author(), s.source(), s.member(), s.pinned())).isTrue();
    }
}
