package app.mnema.learning.catalog.exercise;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.mechanic;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blank;
import static app.mnema.learning.support.StudyFixtures.blankKey;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.category;
import static app.mnema.learning.support.StudyFixtures.image;
import static app.mnema.learning.support.StudyFixtures.item;
import static app.mnema.learning.support.StudyFixtures.option;
import static app.mnema.learning.support.StudyFixtures.quote;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.support.StudyFixtures.video;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ExerciseServiceIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ExerciseService service;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private JdbcClient jdbc;
    @Autowired private MediaCatalog media;
    @Autowired private StudySessionService studies;
    private StudyFixtures fixtures;

    @BeforeEach
    void fixtures() { fixtures = new StudyFixtures(decks, items, service, studies, media, jdbc); }

    @Test
    void removalCompactsCurrentRosterAndPreservesPublishedHistory() {
        Material material = fixtures.material();
        UUID[] ids = new UUID[3];
        UUID[] revisions = new UUID[3];
        for (int index = 0; index < ids.length; index++) {
            JsonNode result = fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q" + index)),
                    blocks(), "answer"));
            ids[index] = UUID.fromString(result.path("exerciseId").textValue());
            revisions[index] = UUID.fromString(result.path("exerciseRevisionId").textValue());
        }
        assertThat(service.list(material.actor(), material.deck(), null, null, null).path("exercises")).hasSize(3);
        long version = fixtures.deckVersion(material);
        assertThatThrownBy(() -> service.delete(material.actor(), material.deck(), ids[1], version - 1))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), material.deck(), ids[1], version))
                .isInstanceOf(ResourceNotFoundException.class);
        service.delete(material.actor(), material.deck(), ids[1], version);

        JsonNode current = service.list(material.actor(), material.deck(), null, null, null);
        assertThat(current.path("total").intValue()).isEqualTo(2);
        assertThat(current.path("exercises").get(0).path("exerciseId").textValue()).isEqualTo(ids[0].toString());
        assertThat(current.path("exercises").get(1).path("exerciseId").textValue()).isEqualTo(ids[2].toString());
        assertThat(current.path("exercises").get(1).path("ordinal").intValue()).isEqualTo(1);
        assertThatThrownBy(() -> service.read(material.actor(), material.deck(), ids[1], null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.read(material.actor(), material.deck(), ids[1], revisions[1])
                .path("exerciseRevisionId").textValue()).isEqualTo(revisions[1].toString());
        assertThat(count("exercise_revision", "deck_id", material.deck())).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_exercise_change "
                        + "WHERE deck_id=:deck AND revision_id IS NULL")
                .param("deck", material.deck()).query(Long.class).single()).isOne();
    }

    @Test
    void everyMechanicIsCreatedReadListedRevisedReusedAndKeptInHistory() {
        Material material = fixtures.material();
        UUID image = fixtures.pendingAsset(material.actor());
        UUID sound = fixtures.pendingAsset(material.actor());
        UUID clip = fixtures.pendingAsset(material.actor());
        UUID blankOne = UUID.randomUUID();
        UUID blankTwo = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID leftOne = UUID.randomUUID(), leftTwo = UUID.randomUUID();
        UUID rightOne = UUID.randomUUID(), rightTwo = UUID.randomUUID();
        UUID orderOne = UUID.randomUUID(), orderTwo = UUID.randomUUID(), orderThree = UUID.randomUUID();
        UUID orderFour = UUID.randomUUID();
        UUID groupOne = UUID.randomUUID(), groupTwo = UUID.randomUUID(), groupThree = UUID.randomUUID();
        UUID sortOne = UUID.randomUUID(), sortTwo = UUID.randomUUID(), sortThree = UUID.randomUUID();
        UUID sortFour = UUID.randomUUID();
        List<ObjectNode> exercises = List.of(
                fixtures.selfCheck(material, blocks(image(image, "Diagram"), text("Explain"), quote(material, material.node())),
                        blocks(audio(sound, "Narration", "spoken answer"), quote(material, material.distractor()))),
                fixtures.freeResponse(material, blocks(video(clip, "Clip", null), text("What is shown?")),
                        blocks(text("Longer explanation")), "memory", "retention"),
                fixtures.cloze(material, blocks(text("Fill in")), StudyFixtures.blocks(text("a "), blank(blankOne, false, 0, true),
                        text(" and "), blank(blankTwo, true, 7, false)), blankKey(blankOne, "alpha"), blankKey(blankTwo, "beta")),
                fixtures.choice(material, true, blocks(quote(material, material.node())),
                        StudyFixtures.blocks().add(option(first, text("one"), image(image, "Pic")))
                                .add(option(second, quote(material, material.distractor()))), first),
                fixtures.match(material, blocks(text("Match")),
                        StudyFixtures.blocks().add(item(leftOne, audio(sound, "Sound", null))).add(item(leftTwo, text("two"))),
                        StudyFixtures.blocks().add(item(rightOne, text("one"))).add(item(rightTwo, image(image, "Alt"))),
                        new UUID[][] {{leftOne, rightOne}, {leftTwo, rightTwo}}),
                fixtures.order(material, blocks(text("Restore")),
                        StudyFixtures.blocks().add(item(orderOne, text("first")))
                                .add(item(orderTwo, quote(material, material.node())))
                                .add(item(orderThree, image(image, "Step three")))
                                .add(item(orderFour, audio(sound, "Step four", "spoken step"))),
                        orderOne, orderTwo, orderThree, orderFour),
                fixtures.categorize(material, blocks(text("Sort")),
                        StudyFixtures.blocks().add(category(groupOne, "Sounds")).add(category(groupTwo, "Pictures"))
                                .add(category(groupThree, "Neither")),
                        StudyFixtures.blocks().add(item(sortOne, audio(sound, "Clip", null)))
                                .add(item(sortTwo, image(image, "Photo"))).add(item(sortThree, text("note")))
                                .add(item(sortFour, text("other note"))),
                        new UUID[][] {{sortOne, groupOne}, {sortTwo, groupTwo}, {sortThree, groupTwo},
                                {sortFour, groupTwo}}));
        long[] mediaRefs = {2, 1, 0, 1, 2, 2, 2};
        long[] contexts = {1, 0, 0, 1, 0, 1, 0};
        for (int index = 0; index < exercises.size(); index++) {
            ObjectNode exercise = exercises.get(index);
            String type = exercise.path("type").textValue();
            JsonNode created = fixtures.publish(material, exercise, "Objective " + type);
            UUID exerciseId = UUID.fromString(created.path("exerciseId").textValue());
            UUID firstRevision = UUID.fromString(created.path("exerciseRevisionId").textValue());
            UUID objective = UUID.fromString(created.path("objectiveId").textValue());
            UUID firstObjectiveRevision = UUID.fromString(created.path("objectiveRevisionId").textValue());

            JsonNode detail = service.read(material.actor(), material.deck(), exerciseId, null);
            assertThat(detail.path("type").textValue()).isEqualTo(type);
            assertThat(detail.path("schemaVersion").intValue()).isEqualTo(2);
            assertThat(detail.path("content")).isEqualTo(exercise.path("content"));
            assertThat(detail.path("answerKey")).isEqualTo(exercise.path("answerKey"));
            assertThat(detail.path("evaluatorPolicy")).isEqualTo(exercise.path("evaluatorPolicy"));
            assertThat(detail.path("subject").path("memberKey").textValue()).isEqualTo(material.member().toString());
            assertThat(detail.path("subject").path("itemRevisionId").textValue()).isEqualTo(material.itemRevision().toString());
            assertThat(detail.path("objective").path("title").textValue()).isEqualTo("Objective " + type);
            assertThat(detail.path("objective").path("memberKey").textValue()).isEqualTo(material.member().toString());
            assertThat(detail.has("bindings")).isFalse();
            assertThat(detail.has("prompt")).isFalse();
            assertThat(detail.path("objective").has("answerContract")).isFalse();
            // server-derived bindings: one ASSESSED subject, one CONTEXT row per quoted material revision
            assertThat(bindings(firstRevision, "ASSESSED")).isOne();
            assertThat(bindings(firstRevision, "CONTEXT")).isEqualTo(contexts[index]);
            assertThat(count("exercise_media_ref", "exercise_revision_id", firstRevision)).isEqualTo(mediaRefs[index]);

            JsonNode listed = service.list(material.actor(), material.deck(), material.member(), "100", null);
            JsonNode row = java.util.stream.StreamSupport.stream(listed.path("exercises").spliterator(), false)
                    .filter(value -> value.path("exerciseId").textValue().equals(exerciseId.toString())).findFirst().orElseThrow();
            assertThat(row.path("type").textValue()).isEqualTo(type);
            assertThat(row.path("objective").path("title").textValue()).isEqualTo("Objective " + type);
            assertThat(row.has("content")).isFalse();

            // revise: new exercise revision and new objective revision with the stable identity retained
            ObjectNode revised = fixtures.createBody(material, exercise.deepCopy(), "ignored");
            revised.set("objective", JSON.createObjectNode().put("operation", "revise")
                    .put("objectiveId", objective.toString())
                    .put("expectedObjectiveRevisionId", firstObjectiveRevision.toString())
                    .put("title", "Renamed " + type));
            revised.put("expectedExerciseRevisionId", firstRevision.toString());
            JsonNode second2 = service.publish(material.actor(), material.deck(), exerciseId, fixtures.deckVersion(material),
                    ExerciseCommand.readUpdate(bytes(revised))).acknowledgement();
            UUID secondRevision = UUID.fromString(second2.path("exerciseRevisionId").textValue());
            UUID secondObjectiveRevision = UUID.fromString(second2.path("objectiveRevisionId").textValue());
            assertThat(second2.path("objectiveId").textValue()).isEqualTo(objective.toString());
            assertThat(secondObjectiveRevision).isNotEqualTo(firstObjectiveRevision);
            assertThat(service.read(material.actor(), material.deck(), exerciseId, null).path("objective").path("title").textValue())
                    .isEqualTo("Renamed " + type);

            // reuse: a third exercise revision pins the second objective revision unchanged
            ObjectNode reused = fixtures.createBody(material, exercise.deepCopy(), "ignored");
            reused.set("objective", JSON.createObjectNode().put("operation", "reuse")
                    .put("objectiveId", objective.toString()).put("objectiveRevisionId", secondObjectiveRevision.toString()));
            reused.put("expectedExerciseRevisionId", secondRevision.toString());
            JsonNode third = service.publish(material.actor(), material.deck(), exerciseId, fixtures.deckVersion(material),
                    ExerciseCommand.readUpdate(bytes(reused))).acknowledgement();
            assertThat(third.path("objectiveRevisionId").textValue()).isEqualTo(secondObjectiveRevision.toString());
            assertThat(count("objective_revision", "objective_id", objective)).isEqualTo(2);
            assertThat(count("exercise_revision", "exercise_id", exerciseId)).isEqualTo(3);

            // immutable history: the first revision still reads with its own objective revision and title
            JsonNode history = service.read(material.actor(), material.deck(), exerciseId, firstRevision);
            assertThat(history.path("objective").path("objectiveRevisionId").textValue()).isEqualTo(firstObjectiveRevision.toString());
            assertThat(history.path("objective").path("title").textValue()).isEqualTo("Objective " + type);
        }
        assertThat(decks.read(material.actor(), material.deck()).path("exerciseCount").intValue()).isEqualTo(7);
    }

    @Test
    void matchAcceptsEveryMediaCombinationThroughTheSharedSlotsAndPinsEachMediaKind() {
        Material material = fixtures.material();
        UUID sound = fixtures.pendingAsset(material.actor()), otherSound = fixtures.pendingAsset(material.actor());
        UUID picture = fixtures.pendingAsset(material.actor()), otherPicture = fixtures.pendingAsset(material.actor());
        UUID clip = fixtures.pendingAsset(material.actor());
        record Combination(String name, com.fasterxml.jackson.databind.node.ObjectNode left,
                           com.fasterxml.jackson.databind.node.ObjectNode right, long refs) { }
        List<Combination> combinations = List.of(
                new Combination("text-text", text("A"), text("a"), 0),
                new Combination("audio-text", audio(sound, "Sound", "A"), text("a"), 1),
                new Combination("text-audio", text("A"), audio(otherSound, "Sound", null), 1),
                new Combination("image-text", image(picture, "Picture"), text("a"), 1),
                new Combination("audio-image", audio(sound, "Sound", null), image(otherPicture, "Picture"), 2),
                new Combination("video-text", video(clip, "Clip", null), quote(material, material.node()), 1),
                new Combination("audio-audio", audio(sound, "Sound", null), audio(otherSound, "Other", null), 2),
                new Combination("image-image", image(picture, "One"), image(otherPicture, "Two"), 2));
        for (Combination combination : combinations) {
            UUID left = UUID.randomUUID(), right = UUID.randomUUID();
            UUID leftTwo = UUID.randomUUID(), rightTwo = UUID.randomUUID();
            ObjectNode exercise = fixtures.match(material, blocks(text(combination.name())),
                    StudyFixtures.blocks().add(item(left, combination.left())).add(item(leftTwo, text("second left"))),
                    StudyFixtures.blocks().add(item(right, combination.right())).add(item(rightTwo, text("second right"))),
                    new UUID[][] {{left, right}, {leftTwo, rightTwo}});
            JsonNode created = fixtures.publish(material, exercise, combination.name());
            UUID revision = UUID.fromString(created.path("exerciseRevisionId").textValue());
            assertThat(count("exercise_media_ref", "exercise_revision_id", revision)).as(combination.name()).isEqualTo(combination.refs());
            assertThat(service.read(material.actor(), material.deck(), UUID.fromString(created.path("exerciseId").textValue()), null)
                    .path("content")).isEqualTo(exercise.path("content"));
        }
        // the pinned kind is the declared kind of each block
        assertThat(jdbc.sql("SELECT DISTINCT media_kind FROM app_learning.exercise_media_ref WHERE deck_id=:deck ORDER BY 1")
                .param("deck", material.deck()).query(String.class).list()).containsExactly("audio", "image", "video");
        assertThat(jdbc.sql("SELECT media_kind FROM app_learning.exercise_media_ref WHERE asset_id=:asset LIMIT 1")
                .param("asset", clip).query(String.class).single()).isEqualTo("video");
    }

    @Test
    void foreignOrDeletedAssetsFailAtomicallyWithoutAdvancingTheDeck() {
        Material material = fixtures.material();
        UUID foreignAsset = fixtures.pendingAsset(UUID.randomUUID());
        UUID deletedAsset = fixtures.pendingAsset(material.actor());
        jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset")
                .param("asset", deletedAsset).update();
        long before = fixtures.deckVersion(material);
        UUID correct = UUID.randomUUID();
        for (UUID asset : new UUID[] {foreignAsset, deletedAsset, UUID.randomUUID()}) {
            for (ObjectNode exercise : List.of(
                    fixtures.freeResponse(material, blocks(audio(asset, "Voice", null), text("Type it")), blocks(), "x"),
                    fixtures.choice(material, false, blocks(text("Pick")), StudyFixtures.blocks()
                            .add(option(correct, image(asset, "Picture"))).add(option(UUID.randomUUID(), text("b"))), correct))) {
                assertThatThrownBy(() -> fixtures.publish(material, exercise)).isInstanceOf(ResourceNotFoundException.class);
            }
        }
        assertThat(fixtures.deckVersion(material)).isEqualTo(before);
        assertThat(count("exercise_revision", "deck_id", material.deck())).isZero();
        assertThat(count("exercise_media_ref", "deck_id", material.deck())).isZero();
    }

    @Test
    void aReadyAssetOfAnotherKindIsRejectedAtPublicationWhilePendingOnesStayAllowed() {
        Material material = fixtures.material();
        UUID picture = fixtures.readyAsset(material.actor(), "image/png");
        UUID sound = fixtures.readyAsset(material.actor(), "audio/mpeg");
        UUID pending = fixtures.pendingAsset(material.actor());
        long before = fixtures.deckVersion(material);
        assertThatThrownBy(() -> fixtures.publish(material,
                fixtures.freeResponse(material, blocks(audio(picture, "Not audio", null), text("Q")), blocks(), "x")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> fixtures.publish(material,
                fixtures.freeResponse(material, blocks(image(sound, "Not an image"), text("Q")), blocks(), "x")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> fixtures.publish(material,
                fixtures.freeResponse(material, blocks(video(sound, "Not a video", null), text("Q")), blocks(), "x")))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(fixtures.deckVersion(material)).isEqualTo(before);
        assertThat(count("exercise_media_ref", "deck_id", material.deck())).isZero();
        fixtures.publish(material, fixtures.freeResponse(material,
                blocks(audio(sound, "Audio", null), image(picture, "Image"), text("Q")), blocks(), "x"));
        fixtures.publish(material, fixtures.freeResponse(material, blocks(audio(pending, "Pending", null), text("Q2")), blocks(), "x"));
        assertThat(ExerciseCommand.MAX_MEDIA_BLOCKS).isEqualTo(MediaCatalog.MAX_EXERCISE_ASSETS);
    }

    @Test
    void materialBlocksQuoteOnlyExistingTextNodesOfThisDecksCurrentRevisions() {
        Material material = fixtures.material();
        Material other = fixtures.material();
        long before = fixtures.deckVersion(material);
        // a node that is not in the pinned revision, a node without text and a node of the wrong revision
        for (ObjectNode block : List.of(
                quote(material, UUID.randomUUID()),
                quote(material, material.divider()))) {
            assertThatThrownBy(() -> fixtures.publish(material,
                    fixtures.freeResponse(material, blocks(text("Q"), block), blocks(), "x")))
                    .as(block.toString()).isInstanceOf(InvalidRequestException.class);
        }
        // a member of another deck and an unknown revision are opaque 404s, both as quote and as subject
        assertThatThrownBy(() -> fixtures.publish(material,
                fixtures.freeResponse(material, blocks(quote(other, other.node())), blocks(), "x")))
                .isInstanceOf(ResourceNotFoundException.class);
        ObjectNode unknownRevision = quote(material, material.node());
        unknownRevision.put("itemRevisionId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> fixtures.publish(material,
                fixtures.freeResponse(material, blocks(unknownRevision), blocks(), "x")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> fixtures.publish(material, fixtures.freeResponse(other, blocks(text("Q")), blocks(), "x")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(fixtures.deckVersion(material)).isEqualTo(before);

        // a quoted text node of a long paragraph is bounded by the slot that quotes it
        Material long300 = fixtures.addMaterial(material.actor(), material.deck(), "x".repeat(301), "short");
        ExerciseCommand.readCreate(bytes(fixtures.createBody(long300,
                fixtures.freeResponse(long300, blocks(quote(long300, long300.node())), blocks(), "x"), "Prompt")));
        fixtures.publish(long300, fixtures.freeResponse(long300, blocks(quote(long300, long300.node())), blocks(), "x"));
        assertThatThrownBy(() -> fixtures.publish(long300, fixtures.choice(long300, false, blocks(text("Pick")),
                StudyFixtures.blocks().add(option(UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd1"),
                        quote(long300, long300.node()))).add(option(UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd2"), text("b"))),
                UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd1")))).isInstanceOf(InvalidRequestException.class);
        Material huge = fixtures.addMaterial(material.actor(), material.deck(), "y".repeat(4_001), "short");
        assertThatThrownBy(() -> fixtures.publish(huge, fixtures.freeResponse(huge, blocks(quote(huge, huge.node())), blocks(), "x")))
                .isInstanceOf(InvalidRequestException.class);
        // a text projection of the whole document quotes both paragraphs on separate lines
        fixtures.publish(material, fixtures.freeResponse(material, blocks(quote(material, material.root())), blocks(), "x"));
    }

    @Test
    void staleDeckRevisionAndVersionConflictsLeaveNothingBehind() {
        Material material = fixtures.material();
        ObjectNode stale = fixtures.createBody(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "x"), "Stale");
        long staleVersion = fixtures.deckVersion(material);
        fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Other")), blocks(), "y"));
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), null, staleVersion,
                ExerciseCommand.readCreate(bytes(stale)))).isInstanceOf(VersionConflictException.class);
        // the right revision with a wrong version number conflicts as well
        ObjectNode current = fixtures.createBody(material, fixtures.freeResponse(material, blocks(text("Q2")), blocks(), "x"), "Current");
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), null, fixtures.deckVersion(material) + 1,
                ExerciseCommand.readCreate(bytes(current)))).isInstanceOf(VersionConflictException.class);
        assertThat(decks.read(material.actor(), material.deck()).path("exerciseCount").intValue()).isOne();
        assertThat(count("memory_objective", "deck_id", material.deck())).isOne();

        // an update must pin the current exercise revision
        JsonNode first = service.list(material.actor(), material.deck(), null, null, null).path("exercises").get(0);
        ObjectNode wrongRevision = fixtures.createBody(material, fixtures.freeResponse(material, blocks(text("Q3")), blocks(), "x"), "Q3");
        wrongRevision.put("expectedExerciseRevisionId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(),
                UUID.fromString(first.path("exerciseId").textValue()), fixtures.deckVersion(material),
                ExerciseCommand.readUpdate(bytes(wrongRevision)))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), UUID.randomUUID(), fixtures.deckVersion(material),
                ExerciseCommand.readUpdate(bytes(wrongRevision)))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unavailableCapabilitiesAreRejectedWithoutWritesAndStructuralErrorsStayInvalid() {
        Material material = fixtures.material();
        long before = fixtures.deckVersion(material);
        // the same fixtures the architect pinned, retargeted at this deck's real material
        for (String name : new String[] {"rejectedAiAssessment", "rejectedSpeechInput"}) {
            ObjectNode command = fixtures.createBody(material, mechanic(name).path("exercise").deepCopy(), "Capability");
            ObjectNode exercise = command.withObject("exercise");
            exercise.set("subject", JSON.createObjectNode().put("memberKey", material.member().toString())
                    .put("itemRevisionId", material.itemRevision().toString()));
            assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), null, before,
                    ExerciseCommand.readCreate(bytes(command)))).as(name).isInstanceOf(CapabilityUnavailableException.class);
        }
        assertThat(fixtures.deckVersion(material)).isEqualTo(before);
        assertThat(count("exercise_revision", "deck_id", material.deck())).isZero();
        assertThat(count("memory_objective", "deck_id", material.deck())).isZero();

        // a broken rubric is a 400 before any capability question
        ObjectNode broken = fixtures.createBody(material, mechanic("rejectedAiAssessment").path("exercise").deepCopy(), "Broken");
        broken.withObject("exercise").withObject("evaluatorPolicy").withObject("rubric").withArray("criteria").removeAll();
        assertThatThrownBy(() -> ExerciseCommand.readCreate(bytes(broken))).isInstanceOf(InvalidRequestException.class);
        // a deterministic free response with typed input needs no capability
        fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "answer"));
    }

    @Test
    void objectiveOperationsEnforceMemberTitleAndRevisionPins() {
        Material material = fixtures.material();
        Material second = fixtures.addMaterial(material.actor(), material.deck(), "other", "another");
        JsonNode created = fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "a"), "Title");
        UUID objective = UUID.fromString(created.path("objectiveId").textValue());
        UUID revision = UUID.fromString(created.path("objectiveRevisionId").textValue());

        // an objective belongs to the material that owns it: reuse and revise from another material are 400
        for (String operation : new String[] {"reuse", "revise"}) {
            ObjectNode body = fixtures.createBody(second, fixtures.freeResponse(second, blocks(text("Q2")), blocks(), "b"), "x");
            ObjectNode objectiveValue = JSON.createObjectNode().put("operation", operation)
                    .put("objectiveId", objective.toString());
            if (operation.equals("reuse")) objectiveValue.put("objectiveRevisionId", revision.toString());
            else objectiveValue.put("expectedObjectiveRevisionId", revision.toString()).put("title", "Hijack");
            body.set("objective", objectiveValue);
            assertThatThrownBy(() -> service.publish(second.actor(), second.deck(), null, fixtures.deckVersion(second),
                    ExerciseCommand.readCreate(bytes(body)))).as(operation).isInstanceOf(InvalidRequestException.class);
        }
        // a stale objective pin conflicts and an unknown objective is opaque
        ObjectNode stale = fixtures.createBody(material, fixtures.freeResponse(material, blocks(text("Q3")), blocks(), "c"), "x");
        stale.set("objective", JSON.createObjectNode().put("operation", "reuse").put("objectiveId", objective.toString())
                .put("objectiveRevisionId", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), null, fixtures.deckVersion(material),
                ExerciseCommand.readCreate(bytes(stale)))).isInstanceOf(VersionConflictException.class);
        stale.set("objective", JSON.createObjectNode().put("operation", "revise").put("objectiveId", objective.toString())
                .put("expectedObjectiveRevisionId", UUID.randomUUID().toString()).put("title", "x"));
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), null, fixtures.deckVersion(material),
                ExerciseCommand.readCreate(bytes(stale)))).isInstanceOf(VersionConflictException.class);
        stale.set("objective", JSON.createObjectNode().put("operation", "reuse").put("objectiveId", UUID.randomUUID().toString())
                .put("objectiveRevisionId", revision.toString()));
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), null, fixtures.deckVersion(material),
                ExerciseCommand.readCreate(bytes(stale)))).isInstanceOf(ResourceNotFoundException.class);
        // another owner sees nothing
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), material.deck(), UUID.randomUUID(), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.list(UUID.randomUUID(), material.deck(), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("memory_objective", "deck_id", material.deck())).isOne();
    }

    @Test
    void retriesConflictsPaginationAndDatabaseImmutabilityAreEnforced() {
        Material material = fixtures.material();
        ObjectNode body = fixtures.createBody(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "a"), "Title");
        long version = fixtures.deckVersion(material);
        ExerciseCommand command = ExerciseCommand.readCreate(bytes(body));
        var first = service.publish(material.actor(), material.deck(), null, version, command);
        assertThat(first.replayed()).isFalse();
        assertThat(service.publish(material.actor(), material.deck(), null, version, command).replayed()).isTrue();
        ObjectNode changed = body.deepCopy();
        changed.withObject("objective").put("title", "Different");
        assertThatThrownBy(() -> service.publish(material.actor(), material.deck(), null, version,
                ExerciseCommand.readCreate(bytes(changed)))).isInstanceOf(IdempotencyConflictException.class);

        JsonNode page = service.list(material.actor(), material.deck(), "1", null);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThatThrownBy(() -> service.list(material.actor(), material.deck(), "0", null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.read(material.actor(), material.deck(), UUID.randomUUID(), null))
                .isInstanceOf(ResourceNotFoundException.class);

        UUID objective = UUID.fromString(first.acknowledgement().path("objectiveId").textValue());
        UUID exercise = UUID.fromString(first.acknowledgement().path("exerciseId").textValue());
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.memory_objective SET created_at=created_at "
                        + "WHERE deck_id=:deck AND objective_id=:objective")
                .param("deck", material.deck()).param("objective", objective).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.exercise_revision WHERE deck_id=:deck AND exercise_id=:exercise")
                .param("deck", material.deck()).param("exercise", exercise).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.objective_revision SET descriptor='{\"schemaVersion\":1,\"title\":\"x\"}'::jsonb "
                        + "WHERE deck_id=:deck").param("deck", material.deck()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistedSchemaHasOnlyTheSevenMechanicsAndAValidatedObjectiveDescriptor() {
        for (String constraint : new String[] {"exercise_revision_exercise_type_check", "study_presentation_exercise_type_check"}) {
            String definition = jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=:name")
                    .param("name", constraint).query(String.class).single();
            assertThat(definition).contains("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER",
                            "CATEGORIZE")
                    .doesNotContain("TYPED", "LISTEN", "SINGLE_CHOICE", "AUDIO_TEXT_MATCH", "CLOZE_SINGLE");
        }
        assertThat(jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname='exercise_revision_answer_key_check'").query(String.class).single())
                .contains("SELF_REPORT", "TEXT", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE");
        assertThat(jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='exercise_revision_schema_version_check'")
                .query(String.class).single()).contains("2");
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='app_learning' "
                + "AND ((table_name='exercise_revision' AND column_name IN ('prompt_spec')) "
                + "OR (table_name='objective_revision' AND column_name='answer_contract') "
                + "OR (table_name='study_presentation' AND column_name IN ('options','bindings','prompt','answer_contract')) "
                + "OR (table_name='exercise_content_binding' AND column_name='display_spec') "
                + "OR (table_name='study_pair_interaction' AND column_name IN ('cue_id','option_id')))")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_proc WHERE proname='exercise_audio_ready'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void anEmptyDeckListsNoExercisesAndForeignDecksAreOpaque() {
        UUID actor = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Empty", "Deck"))
                .acknowledgement().path("deck").path("deckId").textValue());
        assertThat(service.list(actor, deck, null, null).path("exercises")).isEmpty();
        assertThat(service.list(actor, deck, null, null).path("total").intValue()).isZero();
    }

    private long bindings(UUID revision, String role) {
        return jdbc.sql("SELECT count(*) FROM app_learning.exercise_content_binding "
                        + "WHERE exercise_revision_id=:revision AND role=:role")
                .param("revision", revision).param("role", role).query(Long.class).single();
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + "=:value")
                .param("value", value).query(Long.class).single();
    }

}
