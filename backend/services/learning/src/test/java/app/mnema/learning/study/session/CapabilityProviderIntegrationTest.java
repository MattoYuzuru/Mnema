package app.mnema.learning.study.session;

import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.capability.SemanticAssessmentProvider;
import app.mnema.learning.capability.SpeechToTextProvider;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.mechanic;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.attempt;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.support.StudyFixtures.textResponse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * With flags on and test provider beans present the capabilities are available, so such exercises can be
 * published. There is still no evaluator runtime: answers are UNAVAILABLE, never exact-matched, and a
 * deployment that loses the capability stops issuing them.
 */
@SpringBootTest(properties = {"learning.features.ai-assessment.enabled=true",
        "learning.features.speech-to-text.enabled=true"})
@Import(CapabilityProviderIntegrationTest.Providers.class)
class CapabilityProviderIntegrationTest extends PostgresIntegrationTest {
    @TestConfiguration
    static class Providers {
        @Bean SemanticAssessmentProvider semantic() {
            return (rubric, response) -> new SemanticAssessmentProvider.Judgement(
                    SemanticAssessmentProvider.Level.UNAVAILABLE, "stub");
        }

        @Bean SpeechToTextProvider speech() { return asset -> new SpeechToTextProvider.Transcription("stub"); }
    }

    @Autowired private LearningCapabilities capabilities;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private StudySessionRepository repository;
    @Autowired private AttemptService attempts;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;

    @Test
    void availableCapabilitiesPublishButTheSemanticEvaluatorStaysUnavailableAtRuntime() {
        assertThat(capabilities.aiAssessment()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(capabilities.speechToText()).isEqualTo(new LearningCapabilities.Status(true, null));
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        Material material = fixtures.material();
        for (String name : new String[] {"rejectedSpeechInput", "rejectedAiAssessment"}) {
            ObjectNode command = fixtures.createBody(material, mechanic(name).path("exercise").deepCopy(), name);
            command.withObject("exercise").set("subject", JSON.createObjectNode()
                    .put("memberKey", material.member().toString()).put("itemRevisionId", material.itemRevision().toString()));
            exercises.publish(material.actor(), material.deck(), null, fixtures.deckVersion(material),
                    ExerciseCommand.readCreate(bytes(command)));
        }
        JsonNode session = fixtures.session(material, "SCHEDULED", null);
        assertThat(session.path("presentations")).hasSize(2);
        Issued semantic = null;
        for (JsonNode presentation : session.path("presentations")) {
            if (presentation.path("evaluator").path("id").textValue().equals("ai-semantic")) {
                semantic = new Issued(UUID.fromString(session.path("sessionId").textValue()), presentation);
            }
        }
        assertThat(semantic).isNotNull();
        // the rubric is author data: the learner sees only the evaluator identity
        assertThat(session.toString()).doesNotContain("Инерция —", "rubric", "referenceAnswer");
        assertThat(semantic.json().path("content").path("responseInput").textValue()).isEqualTo("TEXT");
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), semantic.session(),
                attempt(semantic, textResponse("свойство тела сохранять скорость"))).outcome();
        assertThat(outcome.path("status").textValue()).isEqualTo("UNAVAILABLE");
        assertThat(outcome.path("feedback").path("reasonCodes").get(0).textValue()).isEqualTo("EVALUATOR_UNAVAILABLE");
        assertThat(outcome.path("evidence").isNull()).isTrue();
        assertThat(outcome.path("transition").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_transition WHERE account_id=:actor")
                .param("actor", material.actor()).query(Long.class).single()).isZero();

        // when the deployment loses the capability the candidate is skipped, never issued
        StudySessionRepository.Session real = repository.session(material.actor(), material.deck(), semantic.session()).orElseThrow();
        StudySessionRepository.Session fresh = new StudySessionRepository.Session(real.accountId(), UUID.randomUUID(),
                real.deckId(), real.mode(), real.status(), real.timezone(), real.localStudyDate(), real.deckRevisionId(),
                real.deckSequence(), real.generationId(), real.policyVersion(), real.configId(), real.reducerId(),
                real.reducerVersion(), real.configHash(), real.seed(), real.budget(), real.maxNewObjectives(), 0, 0, 0, 0,
                0, false, real.includeNew(), real.practiceOrder(), null, 0, real.createdAt(), real.expiresAt(), null);
        var available = repository.eligibleCandidates(fresh, 0, 20, Instant.now(), true);
        var withheld = repository.eligibleCandidates(fresh, 0, 20, Instant.now(), false);
        assertThat(available).hasSize(2);
        assertThat(withheld).hasSize(1);
        assertThat(withheld.getFirst().evaluator().path("id").textValue()).isEqualTo("deterministic-text");
    }
}
