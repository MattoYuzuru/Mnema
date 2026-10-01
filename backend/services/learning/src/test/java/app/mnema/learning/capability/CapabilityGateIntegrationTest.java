package app.mnema.learning.capability;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.fixture;
import static app.mnema.learning.support.ContractFixtures.mechanic;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Both flags on, but no provider implementation exists: the gate must stay closed. */
@SpringBootTest(properties = {"learning.features.ai-assessment.enabled=true",
        "learning.features.speech-to-text.enabled=true"})
class CapabilityGateIntegrationTest extends PostgresIntegrationTest {
    @Autowired private LearningCapabilities capabilities;
    @Autowired private CapabilityController controller;
    @Autowired private ExerciseService exercises;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;

    @Test
    void aFlagAloneNeverMakesACapabilityAvailable() {
        assertThat(capabilities.aiAssessment()).isEqualTo(new LearningCapabilities.Status(false,
                LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED));
        assertThat(capabilities.speechToText().reason()).isEqualTo(LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED);
        JsonNode body = JSON.valueToTree(controller.read().getBody());
        assertThat(body.path("aiAssessment")).isEqualTo(fixture("mechanics.json")
                .path("capabilitiesFlagWithoutProvider").path("aiAssessment"));
        assertThat(body.path("speechToText").path("available").booleanValue()).isFalse();
    }

    @Test
    void publicationOfAnAiOrSpeechDependentExerciseIsAConflictEvenWithTheFlagsOn() {
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        StudyFixtures.Material material = fixtures.material();
        long before = fixtures.deckVersion(material);
        for (String name : new String[] {"rejectedAiAssessment", "rejectedSpeechInput"}) {
            ObjectNode command = fixtures.createBody(material, mechanic(name).path("exercise").deepCopy(), "Capability");
            command.withObject("exercise").set("subject", JSON.createObjectNode()
                    .put("memberKey", material.member().toString()).put("itemRevisionId", material.itemRevision().toString()));
            assertThatThrownBy(() -> exercises.publish(material.actor(), material.deck(), null, before,
                    ExerciseCommand.readCreate(bytes(command)))).as(name).isInstanceOf(CapabilityUnavailableException.class);
        }
        assertThat(fixtures.deckVersion(material)).isEqualTo(before);
        // structural errors are reported before any capability question
        ObjectNode broken = mechanic("rejectedAiAssessment");
        broken.withObject("exercise").withObject("evaluatorPolicy").withObject("rubric").remove("levels");
        assertThatThrownBy(() -> ExerciseCommand.readCreate(bytes(broken))).isInstanceOf(InvalidRequestException.class);
        fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "typed"));
    }
}
