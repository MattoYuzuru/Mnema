package app.mnema.learning.generation;

import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.option;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance of #294 (AI-16) on the Stub, PostgreSQL and the real catalog: a {@code REVISE_ITEM} session rewrites a copy of an existing
 * material and approval is a revise with {@code If-Match}; a {@code REVISE_EXERCISE} session rewrites a copy of an exercise through the
 * exercise pipeline and may redo its audio through the speech executor (the Stub synthesiser, the speech cache); the old revisions stay in history, drift makes the proposal
 * stale, nothing is reserved when a spec is refused.
 */
class GenerationReviseIntegrationTest extends GenerationEditsSupport {
    // ------------------------------------------------------------------------ helpers

    private ObjectNode reviseItem(StudyFixtures.Material material, String instruction) {
        ObjectNode spec = JSON.createObjectNode().put("kind", "REVISE_ITEM").put("instruction", instruction);
        spec.putObject("target").put("memberKey", material.member().toString()).put("itemRevisionId", material.itemRevision().toString());
        return spec;
    }

    private ObjectNode reviseExercise(UUID exercise, UUID revision, String instruction, String voice) {
        ObjectNode spec = JSON.createObjectNode().put("kind", "REVISE_EXERCISE");
        spec.putObject("target").put("exerciseId", exercise.toString()).put("exerciseRevisionId", revision.toString());
        if (instruction != null) spec.put("instruction", instruction);
        if (voice != null) spec.putObject("media").put("action", "AUDIO_REGENERATE").put("voice", voice);
        return spec;
    }

    private record Published(UUID exercise, UUID revision) { }

    /** A SINGLE choice about the material, with a text prompt: the exercise the Stub rewrites. */
    private Published choice(StudyFixtures.Material material, String question) {
        UUID right = UUID.randomUUID();
        UUID wrong = UUID.randomUUID();
        ObjectNode exercise = fixtures.choice(material, false, StudyFixtures.blocks(StudyFixtures.text(question)),
                optionsOf(option(right, StudyFixtures.text("Верный ответ")), option(wrong, StudyFixtures.text("Неверный ответ"))), right);
        JsonNode ack = fixtures.publish(material, exercise, "Цель " + question);
        return new Published(UUID.fromString(ack.path("exerciseId").stringValue(null)), UUID.fromString(ack.path("exerciseRevisionId").stringValue(null)));
    }

    private static ArrayNode optionsOf(ObjectNode... options) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode value : options) array.add(value);
        return array;
    }

    /** A free response with an audio block in its prompt. */
    private Published withAudio(StudyFixtures.Material material, String question, UUID asset) {
        ObjectNode exercise = fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text(question),
                StudyFixtures.audio(asset, "Произношение", "Планировщик выбирает план")), StudyFixtures.blocks(), "ответ");
        JsonNode ack = fixtures.publish(material, exercise, "Озвучка " + question);
        return new Published(UUID.fromString(ack.path("exerciseId").stringValue(null)), UUID.fromString(ack.path("exerciseRevisionId").stringValue(null)));
    }

    private JsonNode exerciseHead(UUID owner, UUID deck, UUID exercise) {
        return exercises.read(owner, deck, exercise, null);
    }

    private static String promptText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        content.path("prompt").forEach(block -> text.append(block.path("text").stringValue("")));
        return text.toString();
    }

    private List<String> reservationStates(UUID owner) {
        return reservationsOf(owner);
    }

    // ------------------------------------------------------------------- REVISE_ITEM

    @Test
    void aMaterialIsRevisedAsACopyApprovedAsAReviseAndTheOldRevisionStaysInHistory() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");

        UUID session = start(owner, deck, reviseItem(material, "Сделай объяснение проще"));
        // the session starts RUNNING with the artifact REVISING (a copy of the material, no model call to make it) and ends in REVIEW
        awaitState(session, "REVIEW");
        Proposal proposal = proposals(owner, deck, session).getFirst();
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("targetKind").stringValue(null)).isEqualTo("ITEM");
        assertThat(detail.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(detail.path("revisions")).hasSize(2);
        assertThat(detail.path("revisions").get(0).path("cause").stringValue(null)).isEqualTo("INITIAL");
        assertThat(detail.path("revisions").get(1).path("cause").stringValue(null)).isEqualTo("EDIT");
        // the first revision is the material as it is: the same JSON as the catalog's head
        JsonNode original = items.read(owner, deck, material.member(), null).path("document");
        JsonNode copy = detail(owner, deck, proposal, "?revisionId=" + detail.path("revisions").get(0).path("revisionId").stringValue(null))
                .path("revision").path("payload").path("document");
        assertThat(JSON.readTree(copy.toString())).isEqualTo(JSON.readTree(original.toString()));
        List<JsonNode> blocks = blocks(detail);
        assertThat(text(blocks.get(0))).isEqualTo("Планировщик выбирает план Переписано: нет.");
        assertThat(id(blocks.get(0))).isEqualTo(material.node());
        // the one turn is a FREE rewrite of every block, paid by one edit turn (4 credits), the session has no batch reservation
        assertThat(detail.path("turns")).hasSize(1);
        assertThat(detail.path("turns").get(0).path("action").stringValue(null)).isEqualTo("FREE");
        assertThat(detail.path("turns").get(0).path("targetNodeIds")).hasSize(3);
        assertThat(detail.path("turns").get(0).path("instruction").stringValue(null)).isEqualTo("Сделай объяснение проще");
        assertThat(debits(owner)).isEqualTo(4);
        // no notification: the owner is waiting in the Workshop
        assertThat(notificationKinds(owner)).isEmpty();

        // approve = a revise of that material, with the deck version in If-Match
        JsonNode head = items.read(owner, deck, material.member(), null);
        MockHttpServletResponse approved = approve(owner, deck, proposal, UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        JsonNode artifact = json(approved).path("artifacts").get(0);
        assertThat(artifact.path("state").stringValue(null)).isEqualTo("PUBLISHED");
        JsonNode ref = artifact.path("publishedRef");
        assertThat(ref.path("kind").stringValue(null)).isEqualTo("ITEM");
        assertThat(ref.path("memberKey").stringValue(null)).isEqualTo(material.member().toString());
        assertThat(ref.path("itemRevisionId").stringValue(null)).isNotEqualTo(material.itemRevision().toString());
        assertThat(ref.path("ordinal").intValue()).isEqualTo(head.path("ordinal").intValue());
        // the material is the same member with a new head; its first block says what the copy said; no material was added
        JsonNode revised = items.read(owner, deck, material.member(), null);
        assertThat(revised.path("itemRevisionId").stringValue(null)).isEqualTo(ref.path("itemRevisionId").stringValue(null));
        assertThat(text(revised.path("document").path("root").path("content").get(0))).isEqualTo("Планировщик выбирает план Переписано: нет.");
        assertThat(materials(owner, deck)).isEqualTo(1);
        // the old revision is history: still readable, still says the old text
        JsonNode old = items.read(owner, deck, material.member(), material.itemRevision());
        assertThat(text(old.path("document").path("root").path("content").get(0))).isEqualTo("Планировщик выбирает план");
        assertThat(sessionState(session)).isEqualTo("CLOSED");
        assertThat(reservationStates(owner)).containsOnly("SETTLED");
    }

    @Test
    void aRevisionThatChangesTheShapeOfTheMaterialIsSavedWithTheStructuralEditsItNeeds() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Proposal proposal = proposal(owner, deck, reviseItem(material, "Перестрой материал"));
        JsonNode current = detail(owner, deck, proposal).path("revision").path("payload").path("document");
        List<JsonNode> before = blocks(detail(owner, deck, proposal));

        // the model rewrote the first paragraph (same block id, new inline ids), dropped the second and added a paragraph and a heading
        ObjectNode first = paragraph("Планировщик сначала оценивает стоимость планов");
        first.put("id", before.get(0).path("id").stringValue(null));
        ObjectNode revised = document(first, heading(2, "Что дальше"), paragraph("Потом выбирает самый дешёвый"), before.get(2).deepCopy());
        ((ObjectNode) revised.path("root")).put("id", current.path("root").path("id").stringValue(null));
        Proposal changed = withDocument(owner, proposal, revised);

        MockHttpServletResponse approved = approve(owner, deck, changed, UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        JsonNode head = items.read(owner, deck, material.member(), null).path("document").path("root").path("content");
        assertThat(head).hasSize(4);
        assertThat(head.get(0).path("id").stringValue(null)).isEqualTo(material.node().toString());
        assertThat(text(head.get(0))).isEqualTo("Планировщик сначала оценивает стоимость планов");
        assertThat(head.get(1).path("type").stringValue(null)).isEqualTo("heading");
        assertThat(text(head.get(2))).isEqualTo("Потом выбирает самый дешёвый");
        assertThat(head.get(3).path("type").stringValue(null)).isEqualTo("divider");
        assertThat(head.get(3).path("id").stringValue(null)).isEqualTo(material.divider().toString());
        // the old revision is the old material
        JsonNode old = items.read(owner, deck, material.member(), material.itemRevision()).path("document").path("root").path("content");
        assertThat(old).hasSize(3);
        assertThat(text(old.get(1))).isEqualTo("Статистика обновляется командой ANALYZE");
    }

    @Test
    void aTargetThatIsNotTheHeadOfTheMaterialIsSourceUnavailableAndNothingIsReserved() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "один", "два");
        ObjectNode spec = reviseItem(material, "Сделай проще");
        reviseTheMaterial(owner, deck, material);

        // an older revision of an owned material is the 409; an unknown revision, a foreign or unknown material is the opaque 404
        problem(create(owner, deck, spec, UUID.randomUUID()), 409, "SOURCE_UNAVAILABLE");
        ((ObjectNode) spec.path("target")).put("itemRevisionId", UUID.randomUUID().toString());
        problem(create(owner, deck, spec, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        ((ObjectNode) spec.path("target")).put("memberKey", UUID.randomUUID().toString());
        problem(create(owner, deck, spec, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        assertThat(reservationStates(owner)).isEmpty();
        assertThat(json(send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/decks/" + deck + "/generation-sessions")))
                .path("items")).isEmpty();
    }

    @Test
    void aMaterialTheModelCannotRewriteInOneTurnIsRefusedBeforeAnythingIsReserved() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // too long for one rewrite (the whole material is the target and the output bound holds it twice)
        StudyFixtures.Material long_ = fixtures.addMaterial(owner, deck, "Очень длинный абзац. ".repeat(500), "второй");
        MockHttpServletResponse tooLong = create(owner, deck, reviseItem(long_, "Сделай короче"), UUID.randomUUID());
        problem(tooLong, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(json(tooLong).path("limit").stringValue(null)).isEqualTo("EDIT_TARGET_SIZE");
        assertThat(json(tooLong).path("limits").path("maxEditTargetTokens").intValue()).isEqualTo(2_050);

        // a text with a telephone number would be overwritten by a placeholder: refused, as for a selection (#293)
        StudyFixtures.Material personal = fixtures.addMaterial(owner, deck, "Звоните +7 (495) 123-45-67 после обеда", "второй");
        MockHttpServletResponse refused = create(owner, deck, reviseItem(personal, "Сделай короче"), UUID.randomUUID());
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("TARGET_PERSONAL_DATA");

        assertThat(reservationStates(owner)).isEmpty();
        assertThat(json(getSessions(owner, deck)).path("items")).isEmpty();
    }

    private MockHttpServletResponse getSessions(UUID owner, UUID deck) throws Exception {
        return send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/decks/" + deck + "/generation-sessions"));
    }

    @Test
    void aMaterialThatMovedAfterTheCopyMakesTheProposalStaleAndItIsNotRetried() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "один абзац", "два абзаца");
        Proposal proposal = proposal(owner, deck, reviseItem(material, "Сделай короче"));
        // the owner saved the material on another device while the proposal waited
        fixtures.addMaterial(owner, deck, "другой материал", "и ещё");
        long version = deckVersion(deck);
        assertThat(version).isPositive();
        reviseTheMaterial(owner, deck, material);

        MockHttpServletResponse stale = approve(owner, deck, proposal, UUID.randomUUID());
        problem(stale, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(stale).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        assertThat(artifactState(proposal.artifact())).isEqualTo("STALE");
        // a stale revision is a copy of what no longer is: "once more" is a new session, not a retry
        Proposal now = fresh(owner, deck, proposal);
        MockHttpServletResponse retry = retry(owner, deck, now, UUID.randomUUID(), now.version());
        problem(retry, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(retry).path("reason").stringValue(null)).isEqualTo("NOT_RETRYABLE");
        // the owner can still reject it
        assertThat(reject(owner, deck, now, UUID.randomUUID(), now.version()).getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ REVISE_EXERCISE

    @Test
    void anExerciseIsRevisedThroughTheExercisePipelineAndApprovalIsARevisionOfTheSameExercise() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Published published = choice(material, "Что делает планировщик?");
        JsonNode before = exerciseHead(owner, deck, published.exercise());
        int debitsBefore = debits(owner);

        UUID session = start(owner, deck, reviseExercise(published.exercise(), published.revision(), "Сделай вопрос проще", null));
        awaitState(session, "REVIEW");
        Proposal proposal = proposals(owner, deck, session).getFirst();
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("targetKind").stringValue(null)).isEqualTo("EXERCISE");
        assertThat(detail.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(detail.path("revisions")).hasSize(2);
        JsonNode command = detail.path("revision").path("payload").path("command");
        // the stub added one sentence to the question; options, answer key and their identifiers are the ones the exercise had
        assertThat(promptText(command.path("exercise").path("content"))).isEqualTo("Что делает планировщик? Переформулировано.");
        assertThat(command.path("exercise").path("answerKey")).isEqualTo(before.path("answerKey"));
        assertThat(command.path("exercise").path("content").path("options")).isEqualTo(before.path("content").path("options"));
        assertThat(command.path("objective").path("operation").stringValue(null)).isEqualTo("reuse");
        assertThat(command.path("objective").path("objectiveId").stringValue(null)).isEqualTo(before.path("objective").path("objectiveId").stringValue(null));
        assertThat(detail.path("display").path("mechanic").stringValue(null)).isEqualTo("CHOICE");
        assertThat(detail.path("sourceRefs")).hasSize(2);
        assertThat(detail.path("sourceRefs").get(1).path("type").stringValue(null)).isEqualTo("EXERCISE");
        // the copy it started from is the stored exercise
        JsonNode copy = detail(owner, deck, proposal, "?revisionId=" + detail.path("revisions").get(0).path("revisionId").stringValue(null))
                .path("revision").path("payload").path("command").path("exercise");
        assertThat(JSON.readTree(copy.path("content").toString())).isEqualTo(JSON.readTree(before.path("content").toString()));
        assertThat(debits(owner) - debitsBefore).isEqualTo(4);
        assertThat(notificationKinds(owner)).isEmpty();

        MockHttpServletResponse approved = approve(owner, deck, proposal, UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        JsonNode ref = json(approved).path("artifacts").get(0).path("publishedRef");
        assertThat(ref.path("kind").stringValue(null)).isEqualTo("EXERCISE");
        assertThat(ref.path("exerciseId").stringValue(null)).isEqualTo(published.exercise().toString());
        assertThat(ref.path("exerciseRevisionId").stringValue(null)).isNotEqualTo(published.revision().toString());
        // the exercise has a new head; the old revision stays in history; no exercise and no objective was added
        JsonNode head = exerciseHead(owner, deck, published.exercise());
        assertThat(head.path("exerciseRevisionId").stringValue(null)).isEqualTo(ref.path("exerciseRevisionId").stringValue(null));
        assertThat(promptText(head.path("content"))).isEqualTo("Что делает планировщик? Переформулировано.");
        assertThat(promptText(exercises.read(owner, deck, published.exercise(), published.revision()).path("content"))).isEqualTo("Что делает планировщик?");
        assertThat(exercises.list(owner, deck, null, null).path("total").intValue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.memory_objective WHERE deck_id=:deck").param("deck", deck).query(Integer.class).single())
                .isEqualTo(1);
        // a revision is not «Новое»
        assertThat(exercises.list(owner, deck, null, null).path("exercises").get(0).path("isNew").booleanValue()).isFalse();
        assertThat(sessionState(session)).isEqualTo("CLOSED");
        assertThat(reservationStates(owner)).containsOnly("SETTLED");
    }

    @Test
    void anExerciseThatMovedAfterTheCopyIsStaleAndAnExerciseThatCannotBeShownToTheModelIsRefused() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Published published = choice(material, "Что делает планировщик?");
        Proposal proposal = proposal(owner, deck, reviseExercise(published.exercise(), published.revision(), "Короче", null));

        // the exercise was saved again (another revision) while the proposal waited: the copy is of what no longer is
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckRevision(deck).toString()).put("expectedExerciseRevisionId", published.revision().toString());
        body.putObject("objective").put("operation", "reuse").put("objectiveId", exerciseHead(owner, deck, published.exercise()).path("objective")
                .path("objectiveId").stringValue(null)).put("objectiveRevisionId", exerciseHead(owner, deck, published.exercise()).path("objective")
                .path("objectiveRevisionId").stringValue(null));
        body.set("exercise", reducedExercise(exerciseHead(owner, deck, published.exercise())));
        exercises.publish(owner, deck, published.exercise(), deckVersion(deck), app.mnema.learning.catalog.exercise.ExerciseCommand
                .readUpdate(new java.io.ByteArrayInputStream(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        MockHttpServletResponse stale = approve(owner, deck, proposal, UUID.randomUUID());
        problem(stale, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(stale).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        assertThat(artifactState(proposal.artifact())).isEqualTo("STALE");

        // a target that is no longer the head of the exercise is refused when the spec is created
        problem(create(owner, deck, reviseExercise(published.exercise(), published.revision(), "Ещё", null), UUID.randomUUID()), 409, "SOURCE_UNAVAILABLE");
        problem(create(owner, deck, reviseExercise(UUID.randomUUID(), published.revision(), "Ещё", null), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");

        // an exercise with an image has no output form: it is edited by hand, and nothing is reserved
        UUID image = fixtures.readyAsset(owner, "image/png");
        ObjectNode withImage = fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Что на картинке?"), StudyFixtures.image(image, "схема")),
                StudyFixtures.blocks(), "ответ");
        JsonNode ack = fixtures.publish(material, withImage, "Картинка");
        int reservations = reservationStates(owner).size();
        MockHttpServletResponse refused = create(owner, deck, reviseExercise(UUID.fromString(ack.path("exerciseId").stringValue(null)),
                UUID.fromString(ack.path("exerciseRevisionId").stringValue(null)), "Проще", null), UUID.randomUUID());
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("TARGET_UNSUPPORTED_BLOCK");
        assertThat(reservationStates(owner)).hasSize(reservations);
    }

    // ------------------------------------------------------------------------ media

    /** The audio of an exercise is redone: a new asset made from the transcript, the voice recorded, a new revision, one clip debited. */
    @Test
    void theVoiceOfAnExerciseIsRedoneWithANewAssetFromItsTranscript() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        UUID asset = fixtures.readyAsset(owner, "audio/mpeg");
        Published published = withAudio(material, "Как это произносится?", asset);

        UUID session = start(owner, deck, reviseExercise(published.exercise(), published.revision(), null, "male"));
        awaitState(session, "REVIEW");
        Proposal proposal = proposals(owner, deck, session).getFirst();
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(detail.path("revisions")).hasSize(2);
        assertThat(detail.path("revisions").get(1).path("cause").stringValue(null)).isEqualTo("MEDIA");
        // the slot is READY on a new asset the speech made (the clip of the transcript), with the requested voice and the language of the transcript
        assertThat(detail.path("mediaSlots")).hasSize(1);
        JsonNode slot = detail.path("mediaSlots").get(0);
        assertThat(slot.path("kind").stringValue(null)).isEqualTo("AUDIO");
        assertThat(slot.path("state").stringValue(null)).isEqualTo("READY");
        String made = slot.path("assetId").stringValue(null);
        assertThat(made).isNotEqualTo(asset.toString());
        assertThat(slot.path("voice").stringValue(null)).isEqualTo("male");
        assertThat(slot.path("lang").stringValue(null)).isEqualTo("ru");
        assertThat(detail.path("mediaSlotCounts").path("ready").intValue()).isEqualTo(1);
        JsonNode content = detail.path("revision").path("payload").path("command").path("exercise").path("content");
        assertThat(content.path("prompt").get(1).path("assetId").stringValue(null)).isEqualTo(made);
        JsonNode turn = detail.path("turns").get(0);
        assertThat(turn.path("action").stringValue(null)).isEqualTo("AUDIO_REGENERATE");
        assertThat(turn.path("status").stringValue(null)).isEqualTo("APPLIED");
        assertThat(turn.path("voice").stringValue(null)).isEqualTo("male");
        // one clip was synthesised (a cache miss) and debited; the hold of the turn ended with the turn
        assertThat(debits(owner)).isEqualTo(10);
        assertThat(reservationStates(owner)).doesNotContain("ACTIVE");
        assertThat(notificationKinds(owner)).isEmpty();
        assertThat(calls(owner)).isEmpty();

        // approval is a revision of the exercise: its audio block names the new asset, which the exercise now pins
        MockHttpServletResponse approved = approve(owner, deck, proposal, UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        JsonNode head = exerciseHead(owner, deck, published.exercise());
        assertThat(head.path("exerciseRevisionId").stringValue(null)).isNotEqualTo(published.revision().toString());
        assertThat(head.path("content").path("prompt").get(1).path("assetId").stringValue(null)).isEqualTo(made);
    }

    /** A recording the owner made has no transcript: there is nothing to speak, so its voice cannot be redone (admission and edit both refuse it). */
    @Test
    void anExerciseWhoseAudioHasNoTranscriptCannotHaveItsVoiceRedone() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        ObjectNode exercise = fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Что слышно?"),
                StudyFixtures.audio(fixtures.readyAsset(owner, "audio/mpeg"), "Запись", null)), StudyFixtures.blocks(), "ответ");
        JsonNode ack = fixtures.publish(material, exercise, "Запись без текста");
        UUID id = UUID.fromString(ack.path("exerciseId").stringValue(null));
        UUID revision = UUID.fromString(ack.path("exerciseRevisionId").stringValue(null));

        MockHttpServletResponse refused = create(owner, deck, reviseExercise(id, revision, null, "male"), UUID.randomUUID());
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("TARGET_NO_AUDIO");
        assertThat(reservationStates(owner)).isEmpty();

        Proposal proposal = proposal(owner, deck, reviseExercise(id, revision, "Проще", null));
        ObjectNode redo = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedRevisionId", proposal.revision().toString())
                .put("action", "AUDIO_REGENERATE").put("voice", "male");
        MockHttpServletResponse edit = edit(owner, deck, proposal, redo);
        problem(edit, 400, "INVALID_REQUEST");
        assertThat(json(edit).path("reason").stringValue(null)).isEqualTo("TARGET_NO_AUDIO");
        assertThat(speech.calls).isEmpty();
    }

    /** Every audio block that has a transcript is made again, each in its own language, and the hold covers one clip per block. */
    @Test
    void everyAudioBlockOfAnExerciseIsMadeAgainInTheNewVoiceAndTheOneWithoutATranscriptIsLeftAlone() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        UUID recording = fixtures.readyAsset(owner, "audio/mpeg");
        ObjectNode exercise = fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Послушайте."),
                StudyFixtures.audio(fixtures.readyAsset(owner, "audio/mpeg"), "Русский", "Привет, мир"),
                StudyFixtures.audio(recording, "Запись", null),
                StudyFixtures.audio(fixtures.readyAsset(owner, "audio/mpeg"), "Японский", "行く")), StudyFixtures.blocks(), "ответ");
        JsonNode ack = fixtures.publish(material, exercise, "Три записи");

        UUID session = start(owner, deck, reviseExercise(UUID.fromString(ack.path("exerciseId").stringValue(null)),
                UUID.fromString(ack.path("exerciseRevisionId").stringValue(null)), null, "male"));
        // two clips are reserved for (the third block has no transcript)
        awaitState(session, "REVIEW");
        Proposal proposal = proposals(owner, deck, session).getFirst();
        JsonNode detail = detail(owner, deck, proposal);

        assertThat(speech.calls).extracting(SpeechSynthesis.Request::lang).containsExactlyInAnyOrder("ru", "ja");
        assertThat(speech.calls).extracting(SpeechSynthesis.Request::voice).containsOnly("male");
        assertThat(debits(owner)).isEqualTo(20);
        JsonNode slots = detail.path("mediaSlots");
        assertThat(slots).hasSize(3);
        assertThat(slots.get(0).path("lang").stringValue(null)).isEqualTo("ru");
        assertThat(slots.get(0).path("voice").stringValue(null)).isEqualTo("male");
        // the block without a transcript keeps its own asset and has no voice
        assertThat(slots.get(1).path("assetId").stringValue(null)).isEqualTo(recording.toString());
        assertThat(slots.get(1).path("voice").isNull()).isTrue();
        assertThat(slots.get(2).path("lang").stringValue(null)).isEqualTo("ja");
        JsonNode prompt = detail.path("revision").path("payload").path("command").path("exercise").path("content").path("prompt");
        assertThat(prompt.get(1).path("assetId").stringValue(null)).isEqualTo(slots.get(0).path("assetId").stringValue(null));
        assertThat(prompt.get(2).path("assetId").stringValue(null)).isEqualTo(recording.toString());
        assertThat(prompt.get(3).path("assetId").stringValue(null)).isEqualTo(slots.get(2).path("assetId").stringValue(null));
        assertThat(reservationStates(owner)).doesNotContain("ACTIVE");

        // «Вернуть» restores the audio the exercise had: the slots follow the revision shown
        MockHttpServletResponse back = revert(owner, deck, proposal, proposal.version(), UUID.fromString(detail.path("revisions").get(0).path("revisionId").stringValue(null)));
        assertThat(back.getStatus()).as(back.getContentAsString()).isEqualTo(200);
        JsonNode restored = detail(owner, deck, proposal).path("mediaSlots");
        assertThat(restored.get(0).path("assetId").stringValue(null)).isNotEqualTo(slots.get(0).path("assetId").stringValue(null));
        assertThat(restored.get(0).path("voice").isNull()).isTrue();
        assertThat(restored.get(1).path("assetId").stringValue(null)).isEqualTo(recording.toString());
    }

    @Test
    void aRewriteAndAVoiceChangeRunInOrderOnOneArtifactAndAFailedRewriteDoesNotStartTheVoice() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        UUID asset = fixtures.readyAsset(owner, "audio/mpeg");
        Published published = withAudio(material, "Как это произносится?", asset);

        UUID session = start(owner, deck, reviseExercise(published.exercise(), published.revision(), "Сделай вопрос короче", "female"));
        awaitState(session, "REVIEW");
        Proposal proposal = proposals(owner, deck, session).getFirst();
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("revisions")).hasSize(3);
        assertThat(detail.path("turns")).hasSize(2);
        assertThat(detail.path("turns").get(0).path("action").stringValue(null)).isEqualTo("FREE");
        assertThat(detail.path("turns").get(1).path("action").stringValue(null)).isEqualTo("AUDIO_REGENERATE");
        assertThat(detail.path("turns").get(1).path("voice").stringValue(null)).isEqualTo("female");
        // the question was rewritten, the audio block now names the clip made on the rewritten revision and the slot follows it
        JsonNode content = detail.path("revision").path("payload").path("command").path("exercise").path("content");
        assertThat(promptText(content)).isEqualTo("Как это произносится? Переформулировано.");
        assertThat(content.path("prompt")).hasSize(2);
        assertThat(content.path("prompt").get(1).path("assetId").stringValue(null)).isNotEqualTo(asset.toString())
                .isEqualTo(detail.path("mediaSlots").get(0).path("assetId").stringValue(null));
        assertThat(detail.path("mediaSlots").get(0).path("voice").stringValue(null)).isEqualTo("female");
        assertThat(debits(owner)).isEqualTo(14);
        assertThat(reservationStates(owner)).hasSize(2).doesNotContain("ACTIVE");

        // a rewrite the model cannot get right fails; the voice change that waited for it is cancelled and its hold released
        UUID second = start(owner, deck, reviseExercise(published.exercise(), published.revision(), "[[stub:broken-key-always]] проще", "male"));
        awaitState(second, "REVIEW");
        Proposal failed = proposals(owner, deck, second).getFirst();
        JsonNode after = detail(owner, deck, failed);
        assertThat(after.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(after.path("revisions")).hasSize(1);
        assertThat(after.path("turns")).hasSize(1);
        assertThat(after.path("turns").get(0).path("status").stringValue(null)).isEqualTo("FAILED");
        assertThat(after.path("turns").get(0).path("errorCode").stringValue(null)).isEqualTo("INVALID_OUTPUT");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_step WHERE session_id=:id AND kind='TTS' AND state='CANCELLED'")
                .param("id", second).query(Integer.class).single()).isEqualTo(1);
        assertThat(debits(owner)).isEqualTo(14);
        assertThat(reservationStates(owner)).doesNotContain("ACTIVE");
        // three provider calls: the answer, the repair and the strong route (the first session made one)
        assertThat(calls(owner).stream().filter(call -> call.prompt().contains("<task kind=\"exercise-edit\">"))).hasSize(4);
    }

    @Test
    void aRepairedAnswerIsAcceptedAndTheEditEndpointRedoesTheVoiceOrRewritesTheWholeExercise() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        UUID asset = fixtures.readyAsset(owner, "audio/mpeg");
        Published published = withAudio(material, "Как это произносится?", asset);
        Proposal proposal = proposal(owner, deck, reviseExercise(published.exercise(), published.revision(), "[[stub:broken-key]] проще", null));
        // the first answer was not an exercise of the schema, the repair was: one repair is made on the same route
        assertThat(calls(owner).stream().filter(call -> call.prompt().contains("<task kind=\"exercise-edit\">")).toList()).hasSize(2);
        assertThat(detail(owner, deck, proposal).path("turns").get(0).path("status").stringValue(null)).isEqualTo("APPLIED");

        // «Ещё раз» is another edit of the whole exercise; the voice is its own action
        Proposal now = current(owner, deck, proposal);
        ObjectNode again = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedRevisionId", now.revision().toString())
                .put("action", "FREE").put("instruction", "Перепиши");
        MockHttpServletResponse accepted = edit(owner, deck, now, again);
        assertThat(accepted.getStatus()).as(accepted.getContentAsString()).isEqualTo(202);
        UUID turn = UUID.fromString(json(accepted).path("turn").path("turnId").stringValue(null));
        assertThat(json(accepted).path("turn").path("targetNodeIds")).isEmpty();
        awaitTurn(turn, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        now = current(owner, deck, proposal);
        ObjectNode voice = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedRevisionId", now.revision().toString())
                .put("action", "AUDIO_REGENERATE").put("voice", "male");
        MockHttpServletResponse redo = edit(owner, deck, now, voice);
        assertThat(redo.getStatus()).as(redo.getContentAsString()).isEqualTo(202);
        assertThat(json(redo).path("turn").path("voice").stringValue(null)).isEqualTo("male");
        assertThat(json(redo).path("artifact").path("state").stringValue(null)).isEqualTo("REVISING");
        UUID redone = UUID.fromString(json(redo).path("turn").path("turnId").stringValue(null));
        awaitTurn(redone, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("revisions")).hasSize(4);
        assertThat(detail.path("mediaSlots").get(0).path("voice").stringValue(null)).isEqualTo("male");

        // the shape of an exercise edit: no blocks, a voice only for the redo of the audio, nothing but the two actions
        Proposal last = current(owner, deck, proposal);
        for (ObjectNode bad : List.of(
                JSON.createObjectNode().put("action", "REWRITE").put("instruction", "x"),
                JSON.createObjectNode().put("action", "REMOVE_MEDIA"),
                JSON.createObjectNode().put("action", "FREE").put("instruction", "x").put("voice", "male"),
                JSON.createObjectNode().put("action", "AUDIO_REGENERATE"),
                JSON.createObjectNode().put("action", "AUDIO_REGENERATE").put("voice", "robot"),
                JSON.createObjectNode().put("action", "FREE").put("instruction", "x").set("target",
                        JSON.createObjectNode().set("nodeIds", JSON.createArrayNode().add(UUID.randomUUID().toString()))))) {
            bad.put("commandId", UUID.randomUUID().toString()).put("expectedRevisionId", last.revision().toString());
            problem(edit(owner, deck, last, bad), 400, "INVALID_REQUEST");
        }
        // an exercise without audio cannot have its voice redone
        Published silent = choice(material, "Без звука?");
        Proposal quiet = proposal(owner, deck, reviseExercise(silent.exercise(), silent.revision(), "Проще", null));
        ObjectNode redoSilent = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedRevisionId", quiet.revision().toString())
                .put("action", "AUDIO_REGENERATE").put("voice", "male");
        MockHttpServletResponse refused = edit(owner, deck, quiet, redoSilent);
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("TARGET_NO_AUDIO");
        MockHttpServletResponse noAudio = create(owner, deck, reviseExercise(silent.exercise(), silent.revision(), null, "male"), UUID.randomUUID());
        problem(noAudio, 400, "INVALID_REQUEST");
        assertThat(json(noAudio).path("reason").stringValue(null)).isEqualTo("TARGET_NO_AUDIO");
    }

    @Test
    void cancellingARevisionThatWaitsForItsRewriteReleasesBothHoldsAndStartsNoVoiceChange() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        UUID asset = fixtures.readyAsset(owner, "audio/mpeg");
        Published published = withAudio(material, "Как это произносится?", asset);

        UUID session = start(owner, deck, reviseExercise(published.exercise(), published.revision(), "[[fake:block]] медленно", "male"));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // two holds, one per turn; the voice change is a step that waits for the rewrite
        assertThat(reservationStates(owner)).containsExactly("ACTIVE", "ACTIVE");
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE session_id=:id AND kind='TTS'").param("id", session)
                .query(String.class).single()).isEqualTo("WAITING_DEPENDENCIES");
        assertThat(json(getSession(owner, deck, session)).path("usage").path("reservedCredits").intValue()).isEqualTo(14);

        assertThat(cancel(owner, deck, session, UUID.randomUUID()).getStatus()).isEqualTo(200);

        Proposal proposal = proposals(owner, deck, session).getFirst();
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE session_id=:id AND kind='TTS'").param("id", session)
                .query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(reservationStates(owner)).containsOnly("RELEASED");
        assertThat(debits(owner)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_artifact_turn WHERE artifact_id=:id AND action='AUDIO_REGENERATE'")
                .param("id", proposal.artifact()).query(Integer.class).single()).isZero();
        provider.release.countDown();
    }

    @Test
    void aRevisedMaterialCanBeHandedOffAsADraftOfThatMaterialAndAnExerciseCannot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "один абзац", "два абзаца");
        Proposal proposal = proposal(owner, deck, reviseItem(material, "Сделай короче"));

        MockHttpServletResponse handed = handoff(owner, deck, proposal, UUID.randomUUID());
        assertThat(handed.getStatus()).as(handed.getContentAsString()).isEqualTo(201);
        JsonNode draft = json(handed).path("draft");
        assertThat(draft.path("memberKey").stringValue(null)).isEqualTo(material.member().toString());
        assertThat(draft.path("baseRevisionId").stringValue(null)).isEqualTo(material.itemRevision().toString());
        assertThat(artifactState(proposal.artifact())).isEqualTo("HANDED_OFF");
        assertThat(sessionState(proposal.session())).isEqualTo("CLOSED");
        // nothing was published: the material is as it was
        assertThat(items.read(owner, deck, material.member(), null).path("itemRevisionId").stringValue(null)).isEqualTo(material.itemRevision().toString());

        Published published = choice(material, "Что делает планировщик?");
        Proposal exercise = proposal(owner, deck, reviseExercise(published.exercise(), published.revision(), "Проще", null));
        problem(handoff(owner, deck, exercise, UUID.randomUUID()), 400, "INVALID_REQUEST");
        // the one artifact of a revision is not a batch
        ObjectNode bulk = bulkBody(UUID.randomUUID(), deckRevision(deck), List.of(exercise));
        problem(approveMany(owner, deck, exercise.session(), bulk, "\"" + deckVersion(deck) + "\""), 400, "INVALID_REQUEST");
    }

    @Test
    void aMaterialThatGrewAfterTheExerciseWasPublishedIsFollowedAtAdmissionAndAtApproval() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Published published = choice(material, "Что делает планировщик?");
        UUID grown = appendParagraph(material, "Новый абзац");

        // the exercise still stands on the old revision of its material: the copy is moved to the head, the exercise has not changed
        Proposal proposal = proposal(owner, deck, reviseExercise(published.exercise(), published.revision(), "Проще", null));
        JsonNode copy = detail(owner, deck, proposal).path("revision").path("payload").path("command").path("exercise");
        assertThat(copy.path("subject").path("itemRevisionId").stringValue(null)).isEqualTo(grown.toString());
        assertThat(detail(owner, deck, proposal).path("sourceRefs").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(grown.toString());

        // it grows again while the proposal waits: approval follows it (the exercise is the same, only its pin moves) and publishes in one go
        UUID again = appendParagraph(material, "Ещё абзац");
        MockHttpServletResponse approved = approve(owner, deck, proposal, UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(exerciseHead(owner, deck, published.exercise()).path("subject").path("itemRevisionId").stringValue(null)).isEqualTo(again.toString());
        List<String> causes = new java.util.ArrayList<>();
        detail(owner, deck, proposal).path("revisions").forEach(revision -> causes.add(revision.path("cause").stringValue(null)));
        assertThat(causes).containsExactly("INITIAL", "EDIT", "REPIN");
    }

    /** A new revision of the material with a paragraph appended: every node that was there is as it was; returns the new revision. */
    private UUID appendParagraph(StudyFixtures.Material material, String paragraph) throws Exception {
        JsonNode head = items.read(material.actor(), material.deck(), material.member(), null);
        JsonNode deckHead = decks.read(material.actor(), material.deck());
        ObjectNode document = (ObjectNode) head.path("document").deepCopy();
        UUID node = UUID.randomUUID();
        ArrayNode content = (ArrayNode) document.path("root").path("content");
        int index = content.size();
        ObjectNode block = content.addObject().put("id", node.toString()).put("type", "paragraph").put("version", 1);
        block.putObject("attrs");
        ObjectNode text = block.putArray("content").addObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("version", 1);
        text.putObject("attrs").put("text", paragraph);
        text.putArray("content");
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckHead.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", head.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", head.path("ordinal").intValue());
        body.set("document", document);
        body.putArray("edits").addObject().put("type", "insert").put("nodeId", node.toString())
                .put("parentId", document.path("root").path("id").stringValue(null)).put("childIndex", index);
        JsonNode ack = items.publish(material.actor(), material.deck(), Long.parseLong(deckHead.path("rowVersion").stringValue(null)),
                app.mnema.learning.catalog.item.ItemPublicationCommand.readSave(new java.io.ByteArrayInputStream(
                        body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), material.member())).acknowledgement();
        return UUID.fromString(ack.path("changes").get(0).path("itemRevisionId").stringValue(null));
    }

    // ------------------------------------------------------------------- review findings

    @Test
    void aVoiceChangeThatWaitedForALongRewriteIsNotExpiredByTheTimeItWaited() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Published published = withAudio(material, "Как это произносится?", fixtures.readyAsset(owner, "audio/mpeg"));

        UUID session = start(owner, deck, reviseExercise(published.exercise(), published.revision(), "[[fake:block]] медленно", "male"));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // the rewrite has taken longer than the queue timeout: the step that waits for it was created long ago
        jdbc.sql("UPDATE app_learning.generation_step SET created_at=CURRENT_TIMESTAMP - interval '10 minutes' WHERE session_id=:id AND kind='TTS'")
                .param("id", session).update();
        provider.release.countDown();

        awaitState(session, "REVIEW");
        Proposal proposal = proposals(owner, deck, session).getFirst();
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("turns")).hasSize(2);
        assertThat(detail.path("turns").get(1).path("action").stringValue(null)).isEqualTo("AUDIO_REGENERATE");
        assertThat(detail.path("turns").get(1).path("status").stringValue(null)).isEqualTo("APPLIED");
        assertThat(detail.path("mediaSlots").get(0).path("voice").stringValue(null)).isEqualTo("male");
    }

    /** SELF_CHECK that quotes the first paragraph of the material. */
    private Published quoting(StudyFixtures.Material material) {
        ObjectNode exercise = fixtures.selfCheck(material, StudyFixtures.blocks(StudyFixtures.text("Что сказано в первом абзаце?")),
                StudyFixtures.blocks(StudyFixtures.quote(material, material.node())));
        JsonNode ack = fixtures.publish(material, exercise, "Первый абзац");
        return new Published(UUID.fromString(ack.path("exerciseId").stringValue(null)), UUID.fromString(ack.path("exerciseRevisionId").stringValue(null)));
    }

    @Test
    void anEditOfABlockTheExerciseDoesNotQuoteIsFollowedAndOfOneItQuotesMakesTheProposalStale() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Published published = quoting(material);

        Proposal unrelated = proposal(owner, deck, reviseExercise(published.exercise(), published.revision(), "Проще", null));
        reviseTheMaterial(owner, deck, material, 1);
        MockHttpServletResponse followed = approve(owner, deck, unrelated, UUID.randomUUID());
        assertThat(followed.getStatus()).as(followed.getContentAsString()).isEqualTo(200);
        List<String> causes = new java.util.ArrayList<>();
        detail(owner, deck, unrelated).path("revisions").forEach(revision -> causes.add(revision.path("cause").stringValue(null)));
        assertThat(causes).containsExactly("INITIAL", "EDIT", "REPIN");

        // the exercise now stands on the new head; editing the paragraph it quotes is a change to what it asks about
        JsonNode head = exerciseHead(owner, deck, published.exercise());
        Proposal quoted = proposal(owner, deck, reviseExercise(published.exercise(), head.path("exerciseRevisionId").stringValue(null) == null ? published.revision()
                : UUID.fromString(head.path("exerciseRevisionId").stringValue(null)), "Короче", null));
        reviseTheMaterial(owner, deck, material, 0);
        MockHttpServletResponse stale = approve(owner, deck, quoted, UUID.randomUUID());
        problem(stale, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(stale).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        assertThat(artifactState(quoted.artifact())).isEqualTo("STALE");
    }

    @Test
    void aReplacementOfARevisionKeepsTheExercisesOwnObjective() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Published published = choice(material, "Что делает планировщик?");
        Proposal proposal = proposal(owner, deck, reviseExercise(published.exercise(), published.revision(), "Проще", null));
        JsonNode command = detail(owner, deck, proposal).path("revision").path("payload").path("command");

        for (ObjectNode objective : List.of(
                JSON.createObjectNode().put("operation", "create").put("title", "Другая цель"),
                JSON.createObjectNode().put("operation", "reuse").put("objectiveId", UUID.randomUUID().toString())
                        .put("objectiveRevisionId", command.path("objective").path("objectiveRevisionId").stringValue(null)))) {
            ObjectNode body = approvalBody(UUID.randomUUID(), proposal, deckRevision(deck));
            ObjectNode replacement = body.putObject("replacement");
            replacement.set("objective", objective);
            replacement.set("exercise", command.path("exercise").deepCopy());
            problem(approve(owner, deck, proposal, body, "\"" + deckVersion(deck) + "\""), 400, "INVALID_REQUEST");
        }
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");

        ObjectNode body = approvalBody(UUID.randomUUID(), proposal, deckRevision(deck));
        ObjectNode replacement = body.putObject("replacement");
        replacement.set("objective", command.path("objective").deepCopy());
        ObjectNode edited = (ObjectNode) command.path("exercise").deepCopy();
        ((ObjectNode) edited.path("content").path("prompt").get(0)).put("text", "Своя формулировка");
        replacement.set("exercise", edited);
        MockHttpServletResponse approved = approve(owner, deck, proposal, body, "\"" + deckVersion(deck) + "\"");
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(promptText(exerciseHead(owner, deck, published.exercise()).path("content"))).isEqualTo("Своя формулировка");
    }

    @Test
    void anExerciseWithPersonalDataInItsTextIsRefusedForTheModelWithItsOwnReason() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика обновляется командой ANALYZE");
        Published phone = choice(material, "Позвоните +7 (495) 123-45-67 и выберите ответ");

        MockHttpServletResponse refused = create(owner, deck, reviseExercise(phone.exercise(), phone.revision(), "Проще", null), UUID.randomUUID());
        problem(refused, 400, "INVALID_REQUEST");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("TARGET_PERSONAL_DATA");
        assertThat(reservationStates(owner)).isEmpty();
    }

    @Test
    void anApprovalIsReplayedForBothKindsAndRefusedForStaleVersionsAndStrangers() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "один абзац", "два абзаца");
        Published published = choice(material, "Что делает планировщик?");
        Proposal item = proposal(owner, deck, reviseItem(material, "Сделай короче"));
        Proposal exercise = proposal(owner, deck, reviseExercise(published.exercise(), published.revision(), "Проще", null));

        // a stranger sees nothing: the opaque 404 on approval and on edit
        problem(approve(stranger, deck, item, approvalBody(UUID.randomUUID(), item, deckRevision(deck)), "\"" + deckVersion(deck) + "\""), 404, "RESOURCE_NOT_FOUND");
        ObjectNode voice = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedRevisionId", exercise.revision().toString())
                .put("action", "FREE").put("instruction", "x");
        problem(edit(stranger, deck, exercise, voice), 404, "RESOURCE_NOT_FOUND");

        // a stale deck version or deck revision is 412 and changes nothing
        String ifMatch = "\"" + deckVersion(deck) + "\"";
        ObjectNode stale = approvalBody(UUID.randomUUID(), item, deckRevision(deck));
        problem(approve(owner, deck, item, stale, "\"" + (deckVersion(deck) + 7) + "\""), 412, "VERSION_CONFLICT");
        ObjectNode wrongRevision = approvalBody(UUID.randomUUID(), item, UUID.randomUUID());
        problem(approve(owner, deck, item, wrongRevision, ifMatch), 412, "VERSION_CONFLICT");
        assertThat(artifactState(item.artifact())).isEqualTo("PROPOSED");

        // the same command again answers what it answered, flagged as a replay
        ObjectNode itemBody = approvalBody(UUID.randomUUID(), item, deckRevision(deck));
        MockHttpServletResponse first = approve(owner, deck, item, itemBody, ifMatch);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse again = approve(owner, deck, item, itemBody, ifMatch);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(again.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(again)).isEqualTo(json(first));

        String next = "\"" + deckVersion(deck) + "\"";
        ObjectNode exerciseBody = approvalBody(UUID.randomUUID(), exercise, deckRevision(deck));
        MockHttpServletResponse second = approve(owner, deck, exercise, exerciseBody, next);
        assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(200);
        MockHttpServletResponse replay = approve(owner, deck, exercise, exerciseBody, next);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(json(second));
        assertThat(exercises.list(owner, deck, null, null).path("total").intValue()).isEqualTo(1);
    }

    /** The exercise as the update command wants it, with a different question. */
    private static JsonNode reducedExercise(JsonNode head) {
        ObjectNode exercise = JSON.createObjectNode().put("type", head.path("type").stringValue(null)).put("schemaVersion", 2).put("enabled", true);
        exercise.set("subject", head.path("subject").deepCopy());
        ObjectNode content = (ObjectNode) head.path("content").deepCopy();
        ((ObjectNode) content.path("prompt").get(0)).put("text", "Другой вопрос про планировщик?");
        exercise.set("content", content);
        exercise.set("answerKey", head.path("answerKey").deepCopy());
        exercise.set("evaluatorPolicy", head.path("evaluatorPolicy").deepCopy());
        return exercise;
    }

    /** The head of the material is replaced by another revision, as a second device would have saved it. */
    private void reviseTheMaterial(UUID owner, UUID deck, StudyFixtures.Material material) throws Exception {
        reviseTheMaterial(owner, deck, material, 0);
    }

    /** As above, replacing the text of the paragraph at {@code paragraph}. */
    private void reviseTheMaterial(UUID owner, UUID deck, StudyFixtures.Material material, int paragraph) throws Exception {
        JsonNode head = items.read(owner, deck, material.member(), null);
        JsonNode deckHead = decks.read(owner, deck);
        ObjectNode document = (ObjectNode) head.path("document").deepCopy();
        ((ObjectNode) document.path("root").path("content").get(paragraph).path("content").get(0).path("attrs")).put("text", "правка с другого устройства");
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckHead.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", head.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", head.path("ordinal").intValue());
        body.set("document", document);
        items.publish(owner, deck, Long.parseLong(deckHead.path("rowVersion").stringValue(null)),
                app.mnema.learning.catalog.item.ItemPublicationCommand.readSave(new java.io.ByteArrayInputStream(
                        body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), material.member()));
    }
}
