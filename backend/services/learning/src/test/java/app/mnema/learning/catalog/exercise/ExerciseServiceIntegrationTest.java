package app.mnema.learning.catalog.exercise;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ExerciseServiceIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private ExerciseService service;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private JdbcClient jdbc;

    @Test
    void ownerCreatesReusesAndRevisesImmutableObjectiveAndExerciseHistory() {
        Fixture fixture = material(UUID.randomUUID());
        UUID command = UUID.randomUUID();
        ExerciseCommand create = command(command, fixture.deckHead(), fixture, "TYPED", "create", null, null);
        var created = service.publish(fixture.actor(), fixture.deck(), null, 1, create);
        JsonNode acknowledgement = created.acknowledgement();
        UUID exercise = UUID.fromString(acknowledgement.path("exerciseId").textValue());
        UUID firstExerciseRevision = UUID.fromString(acknowledgement.path("exerciseRevisionId").textValue());
        UUID objective = UUID.fromString(acknowledgement.path("objectiveId").textValue());
        UUID firstObjectiveRevision = UUID.fromString(acknowledgement.path("objectiveRevisionId").textValue());

        assertThat(created.replayed()).isFalse();
        assertThat(service.publish(fixture.actor(), fixture.deck(), null, 1, create).replayed()).isTrue();
        JsonNode materialExercises = service.list(fixture.actor(), fixture.deck(), fixture.member(), "1", null);
        assertThat(materialExercises.path("exercises")).hasSize(1);
        assertThat(materialExercises.path("total").intValue()).isOne();
        assertThat(materialExercises.path("exercises").get(0).path("objective").path("objectiveId").textValue())
                .isEqualTo(objective.toString());
        assertThat(materialExercises.path("exercises").get(0).path("objective")
                .path("answerContract").path("accepted").get(0).textValue()).isEqualTo("memory");
        assertThat(service.list(fixture.actor(), fixture.deck(), UUID.randomUUID(), "1", null)
                .path("exercises")).isEmpty();
        JsonNode first = service.read(fixture.actor(), fixture.deck(), exercise, null);
        assertThat(first.path("objective").path("objectiveRevisionId").textValue())
                .isEqualTo(firstObjectiveRevision.toString());
        assertThat(first.path("bindings")).hasSize(1);

        JsonNode head2 = decks.read(fixture.actor(), fixture.deck());
        ExerciseCommand reuse = command(UUID.randomUUID(), head2, fixture, "SELF_CHECK", "reuse", objective,
                firstObjectiveRevision, firstExerciseRevision);
        var reused = service.publish(fixture.actor(), fixture.deck(), exercise, 2, reuse);
        UUID secondExerciseRevision = UUID.fromString(reused.acknowledgement().path("exerciseRevisionId").textValue());
        assertThat(reused.acknowledgement().path("objectiveRevisionId").textValue())
                .isEqualTo(firstObjectiveRevision.toString());

        JsonNode head3 = decks.read(fixture.actor(), fixture.deck());
        ExerciseCommand revise = command(UUID.randomUUID(), head3, fixture, "CLOZE_SINGLE", "revise", objective,
                firstObjectiveRevision, secondExerciseRevision);
        var revised = service.publish(fixture.actor(), fixture.deck(), exercise, 3, revise);
        UUID secondObjectiveRevision = UUID.fromString(revised.acknowledgement().path("objectiveRevisionId").textValue());
        assertThat(secondObjectiveRevision).isNotEqualTo(firstObjectiveRevision);
        assertThat(service.read(fixture.actor(), fixture.deck(), exercise, firstExerciseRevision)
                .path("type").textValue()).isEqualTo("TYPED");
        assertThat(service.read(fixture.actor(), fixture.deck(), exercise, null)
                .path("type").textValue()).isEqualTo("CLOZE_SINGLE");
        assertThat(count("objective_revision", "objective_id", objective)).isEqualTo(2);
        assertThat(count("exercise_revision", "exercise_id", exercise)).isEqualTo(3);
        assertThat(decks.read(fixture.actor(), fixture.deck()).path("exerciseCount").intValue()).isOne();
    }

    @Test
    void choiceSupportsPinnedOptionsWhileAclStalePinsAndDeletedNodesFailAtomically() {
        Fixture fixture = material(UUID.randomUUID());
        ObjectNode create = body(UUID.randomUUID(), fixture.deckHead(), fixture, "SINGLE_CHOICE", "create", null, null);
        create.withObject("exercise").set("evaluatorPolicy",
                JSON.createObjectNode().put("id", "deterministic-choice").put("version", "1"));
        ArrayNode bindings = create.withObject("exercise").withArray("bindings");
        bindings.add(binding("OPTION", 1, fixture));
        bindings.add(binding("OPTION", 2, fixture, fixture.distractor()));
        var published = service.publish(fixture.actor(), fixture.deck(), null, 1,
                ExerciseCommand.readCreate(bytes(create)));
        assertThat(service.read(fixture.actor(), fixture.deck(),
                UUID.fromString(published.acknowledgement().path("exerciseId").textValue()), null).path("bindings"))
                .hasSize(3);

        assertThatThrownBy(() -> service.list(UUID.randomUUID(), fixture.deck(), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        Fixture other = material(UUID.randomUUID());
        ObjectNode crossDeck = body(UUID.randomUUID(), decks.read(fixture.actor(), fixture.deck()), other,
                "TYPED", "create", null, null);
        assertThatThrownBy(() -> service.publish(fixture.actor(), fixture.deck(), null, 2,
                ExerciseCommand.readCreate(bytes(crossDeck))))
                .isInstanceOf(ResourceNotFoundException.class);

        ObjectNode badNode = body(UUID.randomUUID(), decks.read(fixture.actor(), fixture.deck()), fixture,
                "TYPED", "create", null, null);
        ((ObjectNode) badNode.path("exercise").path("bindings").get(0)).withArray("nodeIds")
                .set(0, JSON.getNodeFactory().textNode(UUID.randomUUID().toString()));
        assertThatThrownBy(() -> service.publish(fixture.actor(), fixture.deck(), null, 2,
                ExerciseCommand.readCreate(bytes(badNode))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(decks.read(fixture.actor(), fixture.deck()).path("rowVersion").textValue()).isEqualTo("2");
    }

    @Test
    void retriesConflictsPaginationAndDatabaseImmutabilityAreEnforced() {
        Fixture fixture = material(UUID.randomUUID());
        UUID commandId = UUID.randomUUID();
        ExerciseCommand command = command(commandId, fixture.deckHead(), fixture, "TYPED", "create", null, null);
        JsonNode first = service.publish(fixture.actor(), fixture.deck(), null, 1, command).acknowledgement();
        ObjectNode changed = body(commandId, fixture.deckHead(), fixture, "SELF_CHECK", "create", null, null);
        changed.withObject("exercise").set("evaluatorPolicy",
                JSON.createObjectNode().put("id", "self-check").put("version", "1"));
        assertThatThrownBy(() -> service.publish(fixture.actor(), fixture.deck(), null, 1,
                ExerciseCommand.readCreate(bytes(changed)))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.publish(fixture.actor(), fixture.deck(), null, 1,
                command(UUID.randomUUID(), fixture.deckHead(), fixture, "TYPED", "create", null, null)))
                .isInstanceOf(VersionConflictException.class);

        JsonNode page = service.list(fixture.actor(), fixture.deck(), "1", null);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThatThrownBy(() -> service.list(fixture.actor(), fixture.deck(), "0", null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.read(fixture.actor(), fixture.deck(), UUID.randomUUID(), null))
                .isInstanceOf(ResourceNotFoundException.class);

        UUID objective = UUID.fromString(first.path("objectiveId").textValue());
        UUID exercise = UUID.fromString(first.path("exerciseId").textValue());
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.memory_objective SET created_at=created_at "
                        + "WHERE deck_id=:deck AND objective_id=:objective")
                .param("deck", fixture.deck()).param("objective", objective).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.exercise_revision "
                        + "WHERE deck_id=:deck AND exercise_id=:exercise")
                .param("deck", fixture.deck()).param("exercise", exercise).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Fixture material(UUID actor) {
        UUID deck = UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").textValue());
        JsonNode deckHead = decks.read(actor, deck);
        UUID rootNode = UUID.randomUUID(), answerNode = UUID.randomUUID(), distractorNode = UUID.randomUUID();
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        ObjectNode root = document.putObject("root").put("id", rootNode.toString()).put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", answerNode.toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode text = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        text.putObject("attrs").put("text", "memory"); text.putArray("content");
        ObjectNode distractor = root.withArray("content").addObject().put("id", distractorNode.toString())
                .put("type", "paragraph").put("version", 1);
        distractor.putObject("attrs");
        ObjectNode distractorText = distractor.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        distractorText.putObject("attrs").put("text", "forgetting"); distractorText.putArray("content");
        ObjectNode itemBody = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckHead.path("revisionId").textValue());
        itemBody.set("document", document);
        JsonNode item = items.publish(actor, deck, 0, ItemPublicationCommand.readCreate(bytes(itemBody)))
                .acknowledgement().path("changes").get(0);
        return new Fixture(actor, deck, decks.read(actor, deck), UUID.fromString(item.path("memberKey").textValue()),
                UUID.fromString(item.path("itemRevisionId").textValue()), answerNode, distractorNode);
    }

    private static ExerciseCommand command(UUID command, JsonNode deck, Fixture fixture, String type,
                                           String operation, UUID objective, UUID objectiveRevision) {
        return command(command, deck, fixture, type, operation, objective, objectiveRevision, null);
    }

    private static ExerciseCommand command(UUID command, JsonNode deck, Fixture fixture, String type,
                                           String operation, UUID objective, UUID objectiveRevision,
                                           UUID expectedExerciseRevision) {
        ObjectNode body = body(command, deck, fixture, type, operation, objective, objectiveRevision);
        if (expectedExerciseRevision == null) return ExerciseCommand.readCreate(bytes(body));
        body.put("expectedExerciseRevisionId", expectedExerciseRevision.toString());
        return ExerciseCommand.readUpdate(bytes(body));
    }

    private static ObjectNode body(UUID command, JsonNode deck, Fixture fixture, String type,
                                   String operation, UUID objective, UUID objectiveRevision) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").textValue());
        ObjectNode objectiveValue = body.putObject("objective").put("operation", operation);
        if (operation.equals("reuse")) {
            objectiveValue.put("objectiveId", objective.toString())
                    .put("objectiveRevisionId", objectiveRevision.toString());
        } else {
            if (operation.equals("revise")) objectiveValue.put("objectiveId", objective.toString())
                    .put("expectedObjectiveRevisionId", objectiveRevision.toString());
            ObjectNode answer = objectiveValue.putObject("answerContract").put("schemaVersion", 1);
            answer.putArray("normalization").add("UNICODE_NFC").add("TRIM").add("CASE_FOLD");
            answer.putArray("accepted").add(operation.equals("revise") ? "long-term memory" : "memory");
        }
        ObjectNode exercise = body.putObject("exercise").put("type", type).put("schemaVersion", 1)
                .put("enabled", true);
        exercise.putObject("prompt").put("kind", "NODE_TEXT").put("memberKey", fixture.member().toString())
                .put("itemRevisionId", fixture.itemRevision().toString()).put("nodeId", fixture.node().toString());
        exercise.putArray("bindings").add(binding("ASSESSED", 0, fixture));
        exercise.putObject("evaluatorPolicy").put("id", type.equals("SELF_CHECK") ? "self-check" : "deterministic-text")
                .put("version", "1");
        return body;
    }

    private static ObjectNode binding(String role, int ordinal, Fixture fixture) {
        return binding(role, ordinal, fixture, fixture.node());
    }

    private static ObjectNode binding(String role, int ordinal, Fixture fixture, UUID node) {
        ObjectNode binding = JSON.createObjectNode().put("bindingId", UUID.randomUUID().toString())
                .put("role", role).put("memberKey", fixture.member().toString())
                .put("itemRevisionId", fixture.itemRevision().toString()).put("ordinal", ordinal);
        binding.putArray("nodeIds").add(node.toString());
        binding.putObject("display").put("kind", "NODE_TEXT");
        return binding;
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + "=:value")
                .param("value", value).query(Long.class).single();
    }

    private static ByteArrayInputStream bytes(JsonNode value) {
        return new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(UUID actor, UUID deck, JsonNode deckHead, UUID member, UUID itemRevision, UUID node,
                           UUID distractor) { }
}
