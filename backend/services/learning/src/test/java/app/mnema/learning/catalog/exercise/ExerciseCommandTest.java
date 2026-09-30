package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExerciseCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void sharedFixtureIsExecutableAndEnvelopePinsPathAndDeckVersion() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/study/authoring.json"))) root = root.getParent();
        JsonNode fixture = JSON.readTree(Files.readString(root.resolve("contracts/study/authoring.json")));
        ExerciseCommand command = ExerciseCommand.readCreate(bytes(fixture.path("createTyped").toString()));
        assertThat(command.exercise().type()).isEqualTo("TYPED");
        assertThat(command.exercise().bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.role()).isEqualTo("ASSESSED");
            assertThat(binding.nodeIds()).hasSize(1);
        });
        assertThat(command.objective()).isInstanceOf(ExerciseCommand.CreateObjective.class);
        assertThat(command.envelope(UUID.randomUUID(), null, 4).path("expectedDeckVersion").textValue())
                .isEqualTo("4");
    }

    @Test
    void parsesReuseReviseCustomPromptAndUpdatePrecondition() {
        ObjectNode create = valid("SELF_CHECK");
        ObjectNode reuse = create.withObject("objective");
        reuse.remove("answerContract");
        reuse.put("operation", "reuse")
                .put("objectiveId", UUID.randomUUID().toString()).put("objectiveRevisionId", UUID.randomUUID().toString());
        create.withObject("exercise").set("prompt", JSON.createObjectNode().put("kind", "CUSTOM_TEXT").put("text", "Recall"));
        create.withObject("exercise").set("evaluatorPolicy",
                JSON.createObjectNode().put("id", "self-check").put("version", "1"));
        assertThat(ExerciseCommand.readCreate(bytes(create.toString())).objective())
                .isInstanceOf(ExerciseCommand.ReuseObjective.class);

        ObjectNode update = valid("CLOZE_SINGLE").put("expectedExerciseRevisionId", UUID.randomUUID().toString());
        ObjectNode objective = update.withObject("objective").put("operation", "revise")
                .put("objectiveId", UUID.randomUUID().toString())
                .put("expectedObjectiveRevisionId", UUID.randomUUID().toString());
        assertThat(ExerciseCommand.readUpdate(bytes(update.toString())).objective())
                .isInstanceOf(ExerciseCommand.ReviseObjective.class);
        update.remove("expectedExerciseRevisionId");
        assertThatThrownBy(() -> ExerciseCommand.readUpdate(bytes(update.toString())))
                .isInstanceOf(VersionPreconditionRequiredException.class);
    }

    @Test
    void enforcesChoiceCardinalityRolesEvaluatorAndStrictFields() {
        ObjectNode choice = valid("SINGLE_CHOICE");
        choice.withObject("exercise").set("evaluatorPolicy",
                JSON.createObjectNode().put("id", "deterministic-choice").put("version", "1"));
        var bindings = choice.withObject("exercise").withArray("bindings");
        JsonNode assessed = bindings.get(0);
        bindings.add(binding("OPTION", 1, UUID.fromString(assessed.path("memberKey").textValue()),
                UUID.fromString(assessed.path("itemRevisionId").textValue()),
                UUID.fromString(assessed.path("nodeIds").get(0).textValue())));
        bindings.add(binding("OPTION", 2));
        assertThat(ExerciseCommand.readCreate(bytes(choice.toString())).exercise().bindings()).hasSize(3);

        ObjectNode insufficient = choice.deepCopy(); insufficient.withObject("exercise").withArray("bindings").remove(2);
        assertInvalid(insufficient);
        ObjectNode duplicate = choice.deepCopy();
        ((ObjectNode) duplicate.withObject("exercise").withArray("bindings").get(2))
                .put("bindingId", duplicate.path("exercise").path("bindings").get(1).path("bindingId").textValue());
        assertInvalid(duplicate);
        ObjectNode duplicateTarget = choice.deepCopy();
        ObjectNode firstOption = (ObjectNode) duplicateTarget.path("exercise").path("bindings").get(1);
        ObjectNode secondOption = (ObjectNode) duplicateTarget.path("exercise").path("bindings").get(2);
        secondOption.put("memberKey", firstOption.path("memberKey").textValue())
                .put("itemRevisionId", firstOption.path("itemRevisionId").textValue());
        secondOption.set("nodeIds", firstOption.path("nodeIds").deepCopy());
        assertInvalid(duplicateTarget);
        ObjectNode wrongEvaluator = valid("TYPED");
        wrongEvaluator.withObject("exercise").withObject("evaluatorPolicy").put("id", "self-check");
        assertInvalid(wrongEvaluator);
        ObjectNode unknown = valid("TYPED").put("mode", "SCHEDULED");
        assertInvalid(unknown);
    }

    @Test
    void rejectsInvalidAnswersPromptIdsOrdinalsAndOversizedBodies() {
        ObjectNode invalid = valid("TYPED");
        invalid.withObject("objective").withObject("answerContract").withArray("normalization").add("UNKNOWN");
        assertInvalid(invalid);
        invalid = valid("TYPED");
        invalid.withObject("objective").withObject("answerContract").putArray("accepted").add(" ");
        assertInvalid(invalid);
        invalid = valid("TYPED");
        invalid.withObject("exercise").withObject("prompt").put("nodeId", "bad");
        assertInvalid(invalid);
        invalid = valid("TYPED");
        ((ObjectNode) invalid.withObject("exercise").withArray("bindings").get(0)).put("ordinal", -1);
        assertInvalid(invalid);
        byte[] bytes = new byte[262_145];
        java.util.Arrays.fill(bytes, (byte) ' ');
        assertThatThrownBy(() -> ExerciseCommand.readCreate(new ByteArrayInputStream(bytes)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void customAssessedTextRequiresAVisibleAcceptedAnswerAndNoNode() {
        ObjectNode custom = valid("TYPED");
        ObjectNode assessed = (ObjectNode) custom.withObject("exercise").withArray("bindings").get(0);
        assessed.putArray("nodeIds");
        assessed.set("display", JSON.createObjectNode().put("kind", "CUSTOM_TEXT").put("text", "memory"));
        custom.withObject("objective").withObject("answerContract").withArray("accepted")
                .add("recollection");
        assertThat(ExerciseCommand.readCreate(bytes(custom.toString())).exercise().bindings().get(0).display()
                .path("text").textValue()).isEqualTo("memory");

        ObjectNode mismatch = custom.deepCopy();
        mismatch.withObject("objective").withObject("answerContract").withArray("accepted").set(0,
                JSON.getNodeFactory().textNode("different"));
        assertInvalid(mismatch);
        ObjectNode withNode = custom.deepCopy();
        ((ObjectNode) withNode.path("exercise").path("bindings").get(0)).withArray("nodeIds")
                .add(UUID.randomUUID().toString());
        assertInvalid(withNode);
        ObjectNode unknownDisplay = custom.deepCopy();
        ((ObjectNode) unknownDisplay.path("exercise").path("bindings").get(0)).withObject("display")
                .put("unexpected", true);
        assertInvalid(unknownDisplay);
        ObjectNode choice = valid("SINGLE_CHOICE");
        choice.withObject("exercise").withObject("evaluatorPolicy").put("id", "deterministic-choice");
        ArrayNode choices = choice.withObject("exercise").withArray("bindings");
        JsonNode choiceAssessed = choices.get(0);
        choices.add(binding("OPTION", 1, UUID.fromString(choiceAssessed.path("memberKey").textValue()),
                UUID.fromString(choiceAssessed.path("itemRevisionId").textValue()),
                UUID.fromString(choiceAssessed.path("nodeIds").get(0).textValue())));
        choices.add(binding("OPTION", 2));
        assertThat(ExerciseCommand.readCreate(bytes(choice.toString())).exercise().bindings()).hasSize(3);
        ((ObjectNode) choices.get(0)).putArray("nodeIds");
        ((ObjectNode) choices.get(0)).set("display", assessed.path("display"));
        assertInvalid(choice);
    }

    @Test
    void validatesMatchingModeAndClozeBlankWithoutChangingLegacyCommands() {
        ObjectNode typed = valid("TYPED");
        typed.withObject("objective").withObject("answerContract").put("matchingMode", "SOFT");
        assertThat(ExerciseCommand.readCreate(bytes(typed.toString())).objective())
                .isInstanceOf(ExerciseCommand.CreateObjective.class);
        typed.withObject("objective").withObject("answerContract").put("matchingMode", "FUZZY");
        assertInvalid(typed);
        ObjectNode punctuationOnly = valid("TYPED");
        punctuationOnly.withObject("objective").withObject("answerContract")
                .put("matchingMode", "SOFT").withArray("accepted").add("!!!");
        assertInvalid(punctuationOnly);
        punctuationOnly.withObject("objective").withObject("answerContract")
                .put("matchingMode", "STRICT");
        assertThat(ExerciseCommand.readCreate(bytes(punctuationOnly.toString())).objective())
                .isInstanceOf(ExerciseCommand.CreateObjective.class);

        ObjectNode cloze = valid("CLOZE_SINGLE");
        cloze.withObject("exercise").withObject("prompt").putObject("blank")
                .put("mode", "FIXED").put("length", 9);
        assertThat(ExerciseCommand.readCreate(bytes(cloze.toString())).exercise().prompt()
                .path("blank").path("length").intValue()).isEqualTo(9);
        cloze.withObject("exercise").withObject("prompt").withObject("blank").put("length", 4);
        assertInvalid(cloze);

        ObjectNode actual = valid("CLOZE_SINGLE");
        actual.withObject("exercise").withObject("prompt").putObject("blank").put("mode", "ANSWER_LENGTH");
        actual.withObject("objective").withObject("answerContract").withArray("accepted").add("learning");
        assertInvalid(actual);
        actual.withObject("objective").withObject("answerContract").withArray("accepted").remove(1);
        assertThat(ExerciseCommand.readCreate(bytes(actual.toString())).exercise().prompt()
                .path("blank").path("mode").textValue()).isEqualTo("ANSWER_LENGTH");
        ObjectNode wrongType = valid("TYPED");
        wrongType.withObject("exercise").withObject("prompt").putObject("blank").put("mode", "ANSWER_LENGTH");
        assertInvalid(wrongType);
    }

    @Test
    void choiceSupportsMoreThanSixOptionsAndValidatesExactCorrectSets() {
        ObjectNode root = valid("SINGLE_CHOICE");
        root.withObject("exercise").withObject("evaluatorPolicy").put("id", "deterministic-choice");
        ArrayNode bindings = root.withObject("exercise").withArray("bindings");
        JsonNode assessed = bindings.get(0);
        bindings.add(binding("OPTION", 1, UUID.fromString(assessed.path("memberKey").textValue()),
                UUID.fromString(assessed.path("itemRevisionId").textValue()),
                UUID.fromString(assessed.path("nodeIds").get(0).textValue())));
        for (int ordinal = 2; ordinal <= 24; ordinal++) bindings.add(binding("OPTION", ordinal));
        ObjectNode answer = JSON.createObjectNode().put("schemaVersion", 3).put("selectionMode", "MULTIPLE");
        answer.putArray("correctOptionIds").add(bindings.get(1).path("bindingId").textValue())
                .add(bindings.get(20).path("bindingId").textValue());
        answer.putArray("accepted").add("memory").add("attention");
        root.withObject("objective").set("answerContract", answer);
        assertThat(ExerciseCommand.readCreate(bytes(root.toString())).exercise().bindings()).hasSize(25);
        answer.put("selectionMode", "SINGLE"); assertInvalid(root);
        answer.put("selectionMode", "MULTIPLE");
        answer.withArray("correctOptionIds").set(1, JSON.getNodeFactory().textNode(UUID.randomUUID().toString()));
        assertInvalid(root);
    }

    static ObjectNode valid(String type) {
        UUID member = UUID.randomUUID(), revision = UUID.randomUUID(), node = UUID.randomUUID();
        ObjectNode root = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", UUID.randomUUID().toString());
        ObjectNode answer = root.putObject("objective").put("operation", "create").putObject("answerContract")
                .put("schemaVersion", 1);
        answer.putArray("normalization").add("UNICODE_NFC").add("TRIM").add("CASE_FOLD");
        answer.putArray("accepted").add("memory");
        ObjectNode exercise = root.putObject("exercise").put("type", type).put("schemaVersion", 1)
                .put("enabled", true);
        exercise.putObject("prompt").put("kind", "NODE_TEXT").put("memberKey", member.toString())
                .put("itemRevisionId", revision.toString()).put("nodeId", node.toString());
        exercise.putArray("bindings").add(binding("ASSESSED", 0, member, revision, node));
        exercise.putObject("evaluatorPolicy").put("id", type.equals("SELF_CHECK") ? "self-check" : "deterministic-text")
                .put("version", "1");
        return root;
    }

    private static ObjectNode binding(String role, int ordinal) {
        return binding(role, ordinal, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static ObjectNode binding(String role, int ordinal, UUID member, UUID revision, UUID node) {
        ObjectNode value = JSON.createObjectNode().put("bindingId", UUID.randomUUID().toString()).put("role", role)
                .put("memberKey", member.toString()).put("itemRevisionId", revision.toString()).put("ordinal", ordinal);
        value.putArray("nodeIds").add(node.toString());
        value.putObject("display").put("kind", "NODE_TEXT");
        return value;
    }

    private static void assertInvalid(JsonNode node) {
        assertThatThrownBy(() -> ExerciseCommand.readCreate(bytes(node.toString())))
                .isInstanceOf(InvalidRequestException.class);
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
