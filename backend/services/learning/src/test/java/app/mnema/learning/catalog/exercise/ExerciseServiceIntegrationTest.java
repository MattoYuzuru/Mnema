package app.mnema.learning.catalog.exercise;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionCommand;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.study.attempt.AttemptCommand;
import app.mnema.learning.study.attempt.AttemptService;
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
    @Autowired private MediaCatalog media;
    @Autowired private StudySessionService studies;
    @Autowired private AttemptService attempts;

    @Test
    void removalCompactsCurrentRosterAndPreservesPublishedHistory() {
        Fixture fixture = material(UUID.randomUUID());
        UUID[] ids = new UUID[3];
        UUID[] revisions = new UUID[3];
        for (int index = 0; index < ids.length; index++) {
            JsonNode deck = decks.read(fixture.actor(), fixture.deck());
            var result = service.publish(fixture.actor(), fixture.deck(), null,
                    Long.parseLong(deck.path("rowVersion").textValue()),
                    ExerciseCommand.readCreate(bytes(body(UUID.randomUUID(), deck, fixture,
                            "TYPED", "create", null, null)))).acknowledgement();
            ids[index] = UUID.fromString(result.path("exerciseId").textValue());
            revisions[index] = UUID.fromString(result.path("exerciseRevisionId").textValue());
        }
        assertThat(service.list(fixture.actor(), fixture.deck(), null, null, null)
                .path("exercises")).hasSize(3);
        long version = Long.parseLong(decks.read(fixture.actor(), fixture.deck()).path("rowVersion").textValue());
        assertThatThrownBy(() -> service.delete(fixture.actor(), fixture.deck(), ids[1], version - 1))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), fixture.deck(), ids[1], version))
                .isInstanceOf(ResourceNotFoundException.class);
        service.delete(fixture.actor(), fixture.deck(), ids[1], version);

        JsonNode current = service.list(fixture.actor(), fixture.deck(), null, null, null);
        assertThat(current.path("total").intValue()).isEqualTo(2);
        assertThat(current.path("exercises").get(0).path("exerciseId").textValue()).isEqualTo(ids[0].toString());
        assertThat(current.path("exercises").get(1).path("exerciseId").textValue()).isEqualTo(ids[2].toString());
        assertThat(current.path("exercises").get(1).path("ordinal").intValue()).isEqualTo(1);
        assertThatThrownBy(() -> service.read(fixture.actor(), fixture.deck(), ids[1], null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.read(fixture.actor(), fixture.deck(), ids[1], revisions[1])
                .path("exerciseRevisionId").textValue()).isEqualTo(revisions[1].toString());
        assertThatThrownBy(() -> service.delete(fixture.actor(), fixture.deck(), ids[1], version + 1))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("exercise_revision", "deck_id", fixture.deck())).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_exercise_change "
                        + "WHERE deck_id=:deck AND revision_id IS NULL")
                .param("deck", fixture.deck()).query(Long.class).single()).isOne();
    }

    @Test
    void clozeAnswerLengthIsResolvedFromPinnedAnswersForStudyPresentation() {
        Fixture fixture = material(UUID.randomUUID());
        JsonNode deck = decks.read(fixture.actor(), fixture.deck());
        ObjectNode command = body(UUID.randomUUID(), deck, fixture, "CLOZE_SINGLE", "create", null, null);
        command.withObject("objective").withObject("answerContract").withArray("accepted").add("recall");
        command.withObject("exercise").withObject("prompt").putObject("blank")
                .put("mode", "ANSWER_LENGTH");
        service.publish(fixture.actor(), fixture.deck(), null, 1, ExerciseCommand.readCreate(bytes(command)));
        JsonNode started = studies.start(fixture.actor(), fixture.deck(), "UTC", scheduled()).body();
        UUID session = UUID.fromString(started.path("sessionId").textValue());
        JsonNode presentation = studies.read(fixture.actor(), fixture.deck(), session)
                .path("presentations").get(0);
        assertThat(presentation.path("prompt").path("blank").path("mode").textValue())
                .isEqualTo("ANSWER_LENGTH");
        assertThat(presentation.path("prompt").path("blank").path("length").intValue()).isEqualTo(6);
    }

    @Test
    void listeningPublicationsPinOwnedAssetsAndRejectForeignCueAtomically() {
        Fixture fixture = material(UUID.randomUUID());
        UUID firstAsset = media.reserve(fixture.actor(), UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        UUID secondAsset = media.reserve(fixture.actor(), UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        for (String type : new String[] { "LISTEN_CHOICE", "LISTEN_TYPE", "AUDIO_TEXT_MATCH" }) {
            JsonNode head = decks.read(fixture.actor(), fixture.deck());
            ObjectNode body = body(UUID.randomUUID(), head, fixture, type, "create", null, null);
            ObjectNode exercise = body.withObject("exercise");
            exercise.set("evaluatorPolicy", JSON.createObjectNode().put("id", type.equals("AUDIO_TEXT_MATCH")
                    ? "deterministic-audio-match" : type.equals("LISTEN_CHOICE")
                    ? "deterministic-choice" : "deterministic-text").put("version", "1"));
            ArrayNode bindings = exercise.withArray("bindings");
            if (!type.equals("LISTEN_TYPE")) {
                bindings.add(binding("OPTION", 1, fixture));
                bindings.add(binding("OPTION", 2, fixture, fixture.distractor()));
            }
            if (type.equals("AUDIO_TEXT_MATCH")) {
                UUID firstCue = UUID.randomUUID(), secondCue = UUID.randomUUID();
                ObjectNode prompt = JSON.createObjectNode().put("kind", "AUDIO_MATCH")
                        .put("instruction", "Соотнесите записи и текст");
                prompt.putArray("cues").addObject().put("cueId", firstCue.toString())
                        .put("assetId", firstAsset.toString()).put("title", "Первая").put("transcript", "");
                prompt.withArray("cues").addObject().put("cueId", secondCue.toString())
                        .put("assetId", secondAsset.toString()).put("title", "Вторая").put("transcript", "");
                exercise.set("prompt", prompt);
                ObjectNode answer = JSON.createObjectNode().put("schemaVersion", 2);
                answer.putArray("pairs").addObject().put("cueId", firstCue.toString())
                        .put("optionId", bindings.get(1).path("bindingId").textValue());
                answer.withArray("pairs").addObject().put("cueId", secondCue.toString())
                        .put("optionId", bindings.get(2).path("bindingId").textValue());
                body.withObject("objective").set("answerContract", answer);
            } else exercise.set("prompt", JSON.createObjectNode().put("kind", "AUDIO_ASSET")
                    .put("assetId", firstAsset.toString()).put("title", "Первая")
                    .put("instruction", "Прослушайте запись").put("transcript", "memory"));
            var published = service.publish(fixture.actor(), fixture.deck(), null,
                    Long.parseLong(head.path("rowVersion").textValue()), ExerciseCommand.readCreate(bytes(body)));
            UUID revision = UUID.fromString(published.acknowledgement().path("exerciseRevisionId").textValue());
            assertThat(count("exercise_media_ref", "exercise_revision_id", revision))
                    .isEqualTo(type.equals("AUDIO_TEXT_MATCH") ? 2 : 1);
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.exercise_media_ref "
                            + "WHERE deck_id=:deck AND exercise_revision_id=:revision")
                    .param("deck", fixture.deck()).param("revision", revision).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(media.exerciseReady(fixture.actor(), fixture.deck(),
                    UUID.fromString(published.acknowledgement().path("exerciseId").textValue()), revision)).isFalse();
        }

        UUID waitingSession = UUID.fromString(studies.start(fixture.actor(), fixture.deck(), "UTC",
                scheduled()).body().path("sessionId").textValue());
        assertThat(studies.read(fixture.actor(), fixture.deck(), waitingSession).path("status").textValue())
                .isEqualTo("EMPTY");
        readyAudio(firstAsset);
        readyAudio(secondAsset);
        UUID readySession = UUID.fromString(studies.start(fixture.actor(), fixture.deck(), "UTC",
                scheduled()).body().path("sessionId").textValue());
        JsonNode study = studies.read(fixture.actor(), fixture.deck(), readySession);
        assertThat(study.path("presentations")).hasSize(3);
        study.path("presentations").forEach(row -> {
            assertThat(row.path("bindings")).isEmpty();
            assertThat(row.path("reference").isNull()).isTrue();
        });
        JsonNode typed = java.util.stream.StreamSupport.stream(study.path("presentations").spliterator(), false)
                .filter(row -> row.path("type").asText().equals("LISTEN_TYPE")).findFirst().orElseThrow();
        assertThat(typed.path("reference").isNull()).isTrue();
        assertThat(typed.path("prompt").has("transcript")).isFalse();
        UUID typedPresentation = UUID.fromString(typed.path("presentationId").textValue());
        assertThat(studies.revealTranscript(fixture.actor(), fixture.deck(), readySession, typedPresentation,
                typed.path("nonce").textValue()).path("prompt").path("transcript").textValue())
                .isEqualTo("memory");
        assertThat(studies.read(fixture.actor(), fixture.deck(), readySession).path("presentations")
                .findValuesAsText("transcript")).contains("memory");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.study_audio_accommodation "
                        + "WHERE account_id=:actor AND presentation_id=:presentation")
                .param("actor", fixture.actor()).param("presentation", typedPresentation).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE asset_id=:asset").param("asset", firstAsset).update();
        ObjectNode attempt = JSON.createObjectNode().put("attemptId", UUID.randomUUID().toString())
                .put("presentationId", typedPresentation.toString()).put("nonce", typed.path("nonce").textValue())
                .putNull("confidence").put("durationMs", 100);
        attempt.putObject("response").put("kind", "TEXT").put("text", "memory");
        attempt.putArray("hintsUsed");
        JsonNode blocked = attempts.submit(fixture.actor(), fixture.deck(), readySession,
                AttemptCommand.read(bytes(attempt))).outcome();
        assertThat(blocked.path("status").textValue()).isEqualTo("NOT_ASSESSED");
        assertThat(blocked.path("feedback").path("reasonCodes").get(0).textValue()).isEqualTo("MEDIA_NOT_READY");
        assertThat(blocked.path("transition").isNull()).isTrue();

        UUID foreignAsset = media.reserve(UUID.randomUUID(), UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        JsonNode head = decks.read(fixture.actor(), fixture.deck());
        ObjectNode foreign = body(UUID.randomUUID(), head, fixture, "LISTEN_TYPE", "create", null, null);
        foreign.withObject("exercise").set("prompt", JSON.createObjectNode().put("kind", "AUDIO_ASSET")
                .put("assetId", foreignAsset.toString()).put("title", "Чужая запись")
                .put("instruction", "Прослушайте запись").put("transcript", ""));
        assertThatThrownBy(() -> service.publish(fixture.actor(), fixture.deck(), null,
                Long.parseLong(head.path("rowVersion").textValue()), ExerciseCommand.readCreate(bytes(foreign))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(decks.read(fixture.actor(), fixture.deck()).path("rowVersion").textValue())
                .isEqualTo(head.path("rowVersion").textValue());
    }

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
    void customAnswerAndSynonymSurvivePublicationAndAssessWithoutMaterialNode() {
        Fixture fixture = material(UUID.randomUUID());
        ObjectNode command = body(UUID.randomUUID(), fixture.deckHead(), fixture, "TYPED", "create", null, null);
        ObjectNode assessed = (ObjectNode) command.withObject("exercise").withArray("bindings").get(0);
        assessed.putArray("nodeIds");
        assessed.set("display", JSON.createObjectNode().put("kind", "CUSTOM_TEXT")
                .put("text", "long-term memory"));
        command.withObject("objective").withObject("answerContract").putArray("accepted")
                .add("long-term memory").add("durable memory");
        var published = service.publish(fixture.actor(), fixture.deck(), null, 1,
                ExerciseCommand.readCreate(bytes(command)));
        UUID exercise = UUID.fromString(published.acknowledgement().path("exerciseId").textValue());
        JsonNode detail = service.read(fixture.actor(), fixture.deck(), exercise, null);
        assertThat(detail.path("bindings").get(0).path("nodeIds")).isEmpty();
        assertThat(detail.path("bindings").get(0).path("display").path("text").textValue())
                .isEqualTo("long-term memory");
        UUID session = UUID.fromString(studies.start(fixture.actor(), fixture.deck(), "UTC", scheduled())
                .body().path("sessionId").textValue());
        JsonNode presentation = studies.read(fixture.actor(), fixture.deck(), session)
                .path("presentations").get(0);
        assertThat(presentation.path("reference").textValue()).isEqualTo("long-term memory");
        ObjectNode attempt = JSON.createObjectNode().put("attemptId", UUID.randomUUID().toString())
                .put("presentationId", presentation.path("presentationId").textValue())
                .put("nonce", presentation.path("nonce").textValue())
                .putNull("confidence").put("durationMs", 100);
        attempt.putObject("response").put("kind", "TEXT").put("text", "durable memory");
        attempt.putArray("hintsUsed");
        JsonNode outcome = attempts.submit(fixture.actor(), fixture.deck(), session,
                AttemptCommand.read(bytes(attempt))).outcome();
        assertThat(outcome.path("evidence").path("result").textValue()).isEqualTo("CORRECT");

        ObjectNode reuse = body(UUID.randomUUID(), decks.read(fixture.actor(), fixture.deck()), fixture,
                "TYPED", "reuse", UUID.fromString(published.acknowledgement().path("objectiveId").textValue()),
                UUID.fromString(published.acknowledgement().path("objectiveRevisionId").textValue()));
        ObjectNode unrelated = (ObjectNode) reuse.withObject("exercise").withArray("bindings").get(0);
        unrelated.putArray("nodeIds");
        unrelated.set("display", JSON.createObjectNode().put("kind", "CUSTOM_TEXT").put("text", "wrong answer"));
        assertThatThrownBy(() -> service.publish(fixture.actor(), fixture.deck(), null, 2,
                ExerciseCommand.readCreate(bytes(reuse)))).isInstanceOf(InvalidRequestException.class);
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

    private void readyAudio(UUID asset) {
        UUID blob = UUID.randomUUID();
        byte[] hash = new byte[32];
        java.nio.ByteBuffer.wrap(hash).putLong(blob.getMostSignificantBits()).putLong(blob.getLeastSignificantBits());
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) "
                + "VALUES (:blob,:hash,32,'audio/mpeg',:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", hash).param("key", "audio/" + blob).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state='PROCESSING',updated_at=CURRENT_TIMESTAMP "
                + "WHERE asset_id=:asset").param("asset", asset).update();
        assertThat(media.ready(asset, 0, blob)).isTrue();
    }

    private static StudySessionCommand scheduled() {
        ObjectNode command = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("mode", "SCHEDULED");
        command.putObject("budget").put("maxPresentations", 20).put("maxNewObjectives", 5);
        return StudySessionCommand.read(bytes(command));
    }


    private static ByteArrayInputStream bytes(JsonNode value) {
        return new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(UUID actor, UUID deck, JsonNode deckHead, UUID member, UUID itemRevision, UUID node,
                           UUID distractor) { }
}
