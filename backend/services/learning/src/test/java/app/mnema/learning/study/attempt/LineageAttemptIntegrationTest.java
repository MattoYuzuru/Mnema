package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.attempt;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.support.StudyFixtures.textResponse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/5 (#427): a source and its copy share exercise ids, and one ACCOUNT may own both decks. "The learner already attempted
 * this exercise" (the first-attempt strictness cap) is progress, so it belongs to the learner's deck: an attempt in the source
 * must not count in the copy, nor the other way round.
 */
@SpringBootTest
class LineageAttemptIntegrationTest extends PostgresIntegrationTest {
    @Autowired private AssessmentRepository assessments;
    @Autowired private AttemptService attempts;
    @Autowired private StudySessionService study;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fixtures;
    private SharedScopeFixture shared;

    @BeforeEach
    void fixtures() {
        fixtures = new StudyFixtures(decks, items, exercises, study, media, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    private static UUID exerciseOf(Issued issued) {
        return UUID.fromString(issued.json().path("exerciseRevisionId").stringValue(null));
    }

    private UUID exerciseId(UUID revision) {
        return jdbc.sql("SELECT exercise_id FROM app_learning.exercise_revision WHERE revision_id=:r").param("r", revision)
                .query(UUID.class).single();
    }

    @Test
    void anAttemptInTheSourceIsNotAnAttemptInACopyOfTheSameAccountAndViceVersa() {
        Material a = fixtures.material();
        JsonNode first = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("First")), blocks(), "memory"), "First");
        JsonNode second = fixtures.publish(a, fixtures.freeResponse(a, blocks(text("Second")), blocks(), "memory"), "Second");
        UUID firstExercise = UUID.fromString(first.path("exerciseId").stringValue(null));
        UUID secondExercise = UUID.fromString(second.path("exerciseId").stringValue(null));
        Copy copy = shared.copyForSameOwner(a.deck());
        assertThat(copy.owner()).isEqualTo(a.actor());
        Material ca = new Material(copy.owner(), copy.deckId(), a.member(), a.itemRevision(), a.node(), a.distractor(), a.divider(), a.root());

        // the source answers the first exercise only, the copy (same account) the second only
        List<Issued> inSource = fixtures.issue(a, "SCHEDULED", null);
        Issued sourceAnswer = inSource.stream().filter(p -> exerciseId(exerciseOf(p)).equals(firstExercise)).findFirst().orElseThrow();
        assertThat(attempts.submit(a.actor(), a.deck(), sourceAnswer.session(), attempt(sourceAnswer, textResponse("memory")))
                .outcome().path("feedback").path("result").stringValue(null)).isEqualTo("CORRECT");
        List<Issued> inCopy = fixtures.issue(ca, "SCHEDULED", null);
        Issued copyAnswer = inCopy.stream().filter(p -> exerciseId(exerciseOf(p)).equals(secondExercise)).findFirst().orElseThrow();
        assertThat(attempts.submit(copy.owner(), copy.deckId(), copyAnswer.session(), attempt(copyAnswer, textResponse("memory")))
                .outcome().path("feedback").path("result").stringValue(null)).isEqualTo("CORRECT");

        assertThat(assessments.attemptedExercise(a.actor(), a.deck(), firstExercise, 0)).isTrue();
        assertThat(assessments.attemptedExercise(a.actor(), copy.deckId(), firstExercise, 0)).isFalse();
        assertThat(assessments.attemptedExercise(a.actor(), copy.deckId(), secondExercise, 0)).isTrue();
        assertThat(assessments.attemptedExercise(a.actor(), a.deck(), secondExercise, 0)).isFalse();
        // another epoch is another question
        assertThat(assessments.attemptedExercise(a.actor(), a.deck(), firstExercise, 1)).isFalse();
    }
}
