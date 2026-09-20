package app.mnema.learning.study.session;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StudySessionServiceIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private StudySessionService service;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private JdbcClient jdbc;

    @Test
    void scheduledPreparationPinsPresentationAndExactRetryResumesIt() {
        Fixture fixture = materialWithExercise();
        StudySessionCommand command = scheduled(UUID.randomUUID(), 20);

        StudySessionService.StartResult started = service.start(fixture.actor(), fixture.deck(),
                "Europe/Moscow", command);
        assertThat(started.preparing()).isTrue();
        assertThat(started.body().path("status").textValue()).isEqualTo("PREPARING");
        assertThat(service.start(fixture.actor(), fixture.deck(), "Pacific/Honolulu", command).replayed()).isTrue();

        UUID session = UUID.fromString(started.body().path("sessionId").textValue());
        JsonNode active = service.read(fixture.actor(), fixture.deck(), session);
        JsonNode presentation = active.path("presentations").get(0);
        assertThat(active.path("status").textValue()).isEqualTo("ACTIVE");
        assertThat(active.path("timezone").textValue()).isEqualTo("Europe/Moscow");
        assertThat(active.path("budget").path("maxPresentations").intValue()).isEqualTo(20);
        assertThat(active.path("budget").path("maxNewObjectives").intValue()).isEqualTo(20);
        assertThat(active.path("issuedCount").intValue()).isOne();
        assertThat(active.path("presentations")).hasSize(1);
        assertThat(presentation.path("type").textValue()).isEqualTo("TYPED");
        assertThat(presentation.path("prompt").path("text").textValue()).isEqualTo("memory");

        reviseExercise(fixture);
        JsonNode resumed = service.presentations(fixture.actor(), fixture.deck(), session);
        assertThat(resumed.path("presentations").get(0)).isEqualTo(presentation);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.study_presentation SET prompt=prompt "
                        + "WHERE session_id=:session").param("session", session).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emptyPracticeIsolationExpiryAndCompletedSameDayReplayAreEnforced() {
        UUID emptyActor = UUID.randomUUID();
        UUID emptyDeck = createDeck(emptyActor);
        StudySessionService.StartResult empty = service.start(emptyActor, emptyDeck, "not/a-zone",
                scheduled(UUID.randomUUID(), 20));
        assertThat(empty.preparing()).isFalse();
        assertThat(empty.body().path("status").textValue()).isEqualTo("EMPTY");
        assertThat(empty.body().path("timezone").textValue()).isEqualTo("UTC");

        Fixture fixture = materialWithExercise();
        StudySessionService.StartResult practiceStart = service.start(fixture.actor(), fixture.deck(), "UTC",
                practice(UUID.randomUUID(), false));
        UUID practiceSession = UUID.fromString(practiceStart.body().path("sessionId").textValue());
        assertThat(service.read(fixture.actor(), fixture.deck(), practiceSession).path("status").textValue())
                .isEqualTo("EMPTY");

        StudySessionService.StartResult scheduledStart = service.start(fixture.actor(), fixture.deck(), "UTC",
                scheduled(UUID.randomUUID(), 20));
        UUID source = UUID.fromString(scheduledStart.body().path("sessionId").textValue());
        JsonNode sourceActive = service.read(fixture.actor(), fixture.deck(), source);
        StudySessionService.StartResult introducedPractice = service.start(fixture.actor(), fixture.deck(), "UTC",
                practice(UUID.randomUUID(), false));
        assertThat(service.read(fixture.actor(), fixture.deck(),
                UUID.fromString(introducedPractice.body().path("sessionId").textValue())).path("status").textValue())
                .isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), fixture.deck(), source))
                .isInstanceOf(ResourceNotFoundException.class);

        jdbc.sql("UPDATE app_learning.study_session SET status='COMPLETE',completed_at=statement_timestamp() "
                        + "WHERE account_id=:actor AND session_id=:session")
                .param("actor", fixture.actor()).param("session", source).update();
        JsonNode sources = service.replaySources(fixture.actor(), fixture.deck(), "UTC");
        assertThat(sources.path("items")).hasSize(1);
        assertThat(sources.path("items").get(0).path("sessionId").textValue()).isEqualTo(source.toString());
        StudySessionService.StartResult replay = service.start(fixture.actor(), fixture.deck(), "UTC",
                replay(UUID.randomUUID(), source));
        assertThat(replay.body().path("status").textValue()).isEqualTo("ACTIVE");
        assertThat(replay.body().path("presentations")).hasSize(1);
        assertThat(replay.body().path("presentations").get(0).path("exerciseRevisionId"))
                .isEqualTo(sourceActive.path("presentations").get(0).path("exerciseRevisionId"));

        jdbc.sql("UPDATE app_learning.study_session SET expires_at=created_at + interval '1 millisecond' "
                        + "WHERE account_id=:actor AND session_id=:session")
                .param("actor", fixture.actor()).param("session", source).update();
        assertThatThrownBy(() -> service.read(fixture.actor(), fixture.deck(), source))
                .isInstanceOf(StudySessionExpiredException.class);
    }

    @Test
    void quickBudgetSelectsKnownFirstAndIntroducesAtMostTwoObjectives() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        for (int index = 0; index < 12; index++) addMaterialWithExercise(actor, deck);

        StudySessionService.StartResult introduction = service.start(actor, deck, "UTC",
                scheduled(UUID.randomUUID(), 8, 8));
        JsonNode introduced = service.read(actor, deck,
                UUID.fromString(introduction.body().path("sessionId").textValue()));
        Set<String> known = new HashSet<>();
        introduced.path("presentations").forEach(value -> known.add(value.path("objectiveId").textValue()));
        assertThat(known).hasSize(8);

        StudySessionService.StartResult quickStart = service.start(actor, deck, "UTC",
                scheduled(UUID.randomUUID(), 10, 2));
        JsonNode quick = quickStart.preparing() ? service.read(actor, deck,
                UUID.fromString(quickStart.body().path("sessionId").textValue())) : quickStart.body();
        assertThat(quick.path("budget").path("maxPresentations").intValue()).isEqualTo(10);
        assertThat(quick.path("budget").path("maxNewObjectives").intValue()).isEqualTo(2);
        assertThat(quick.path("presentations")).hasSize(10);
        assertThat(quick.path("presentations").findValuesAsText("objectiveId").stream()
                .filter(value -> !known.contains(value))).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_transition WHERE account_id=:actor")
                .param("actor", actor).query(Long.class).single()).isZero();
    }

    private Fixture materialWithExercise() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        return addMaterialWithExercise(actor, deck);
    }

    private Fixture addMaterialWithExercise(UUID actor, UUID deck) {
        JsonNode deckHead = decks.read(actor, deck);
        long itemExpectedVersion = Long.parseLong(deckHead.path("rowVersion").textValue());
        UUID answerNode = UUID.randomUUID();
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        ObjectNode root = document.putObject("root").put("id", UUID.randomUUID().toString())
                .put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", answerNode.toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode text = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        text.putObject("attrs").put("text", "memory");
        text.putArray("content");
        ObjectNode itemBody = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckHead.path("revisionId").textValue());
        itemBody.set("document", document);
        JsonNode item = items.publish(actor, deck, itemExpectedVersion, ItemPublicationCommand.readCreate(bytes(itemBody)))
                .acknowledgement().path("changes").get(0);
        Fixture initial = new Fixture(actor, deck, UUID.fromString(item.path("memberKey").textValue()),
                UUID.fromString(item.path("itemRevisionId").textValue()), answerNode, null, null, null);
        long exerciseExpectedVersion = Long.parseLong(decks.read(actor, deck).path("rowVersion").textValue());
        ExerciseService.WriteResult published = exercises.publish(actor, deck, null, exerciseExpectedVersion,
                ExerciseCommand.readCreate(bytes(exerciseBody(initial, "create", null, null, null))));
        JsonNode result = published.acknowledgement();
        return new Fixture(actor, deck, initial.member(), initial.itemRevision(), answerNode,
                UUID.fromString(result.path("exerciseId").textValue()),
                UUID.fromString(result.path("exerciseRevisionId").textValue()),
                UUID.fromString(result.path("objectiveId").textValue()));
    }

    private void reviseExercise(Fixture fixture) {
        JsonNode current = exercises.read(fixture.actor(), fixture.deck(), fixture.exercise(), null);
        UUID objectiveRevision = UUID.fromString(current.path("objective").path("objectiveRevisionId").textValue());
        ObjectNode body = exerciseBody(fixture, "reuse", fixture.objective(), objectiveRevision,
                fixture.exerciseRevision());
        exercises.publish(fixture.actor(), fixture.deck(), fixture.exercise(), 2,
                ExerciseCommand.readUpdate(bytes(body)));
    }

    private ObjectNode exerciseBody(Fixture fixture, String operation, UUID objective, UUID objectiveRevision,
                                    UUID expectedExerciseRevision) {
        JsonNode deck = decks.read(fixture.actor(), fixture.deck());
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").textValue());
        if (expectedExerciseRevision != null) {
            body.put("expectedExerciseRevisionId", expectedExerciseRevision.toString());
        }
        ObjectNode objectiveValue = body.putObject("objective").put("operation", operation);
        if (operation.equals("reuse")) {
            objectiveValue.put("objectiveId", objective.toString())
                    .put("objectiveRevisionId", objectiveRevision.toString());
        } else {
            ObjectNode answer = objectiveValue.putObject("answerContract").put("schemaVersion", 1);
            answer.putArray("normalization").add("UNICODE_NFC").add("TRIM").add("CASE_FOLD");
            answer.putArray("accepted").add("memory");
        }
        ObjectNode exercise = body.putObject("exercise").put("type", expectedExerciseRevision == null
                        ? "TYPED" : "SELF_CHECK").put("schemaVersion", 1).put("enabled", true);
        exercise.putObject("prompt").put("kind", "NODE_TEXT").put("memberKey", fixture.member().toString())
                .put("itemRevisionId", fixture.itemRevision().toString()).put("nodeId", fixture.node().toString());
        ObjectNode binding = exercise.putArray("bindings").addObject().put("bindingId", UUID.randomUUID().toString())
                .put("role", "ASSESSED").put("memberKey", fixture.member().toString())
                .put("itemRevisionId", fixture.itemRevision().toString()).put("ordinal", 0);
        binding.putArray("nodeIds").add(fixture.node().toString());
        binding.putObject("display").put("kind", "NODE_TEXT");
        exercise.putObject("evaluatorPolicy").put("id", expectedExerciseRevision == null
                ? "deterministic-text" : "self-check").put("version", "1");
        return body;
    }

    private UUID createDeck(UUID actor) {
        return UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").textValue());
    }

    private static StudySessionCommand scheduled(UUID command, int budget) {
        return scheduled(command, budget, budget);
    }

    private static StudySessionCommand scheduled(UUID command, int budget, int maxNew) {
        return read(JSON.createObjectNode().put("commandId", command.toString()).put("mode", "SCHEDULED")
                .set("budget", JSON.createObjectNode().put("maxPresentations", budget)
                        .put("maxNewObjectives", maxNew)));
    }

    private static StudySessionCommand practice(UUID command, boolean includeNew) {
        ObjectNode value = JSON.createObjectNode().put("commandId", command.toString()).put("mode", "PRACTICE")
                .put("includeNew", includeNew).put("order", "WEAKEST_FIRST");
        value.set("budget", JSON.createObjectNode().put("maxPresentations", 20));
        return read(value);
    }

    private static StudySessionCommand replay(UUID command, UUID source) {
        ObjectNode value = JSON.createObjectNode().put("commandId", command.toString()).put("mode", "REPLAY")
                .put("sourceSessionId", source.toString());
        value.set("budget", JSON.createObjectNode().put("maxPresentations", 20));
        return read(value);
    }

    private static StudySessionCommand read(JsonNode value) { return StudySessionCommand.read(bytes(value)); }
    private static ByteArrayInputStream bytes(JsonNode value) {
        return new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(UUID actor, UUID deck, UUID member, UUID itemRevision, UUID node,
                           UUID exercise, UUID exerciseRevision, UUID objective) { }
}
