package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
        bindings.add(binding("OPTION", 1)); bindings.add(binding("OPTION", 2));
        assertThat(ExerciseCommand.readCreate(bytes(choice.toString())).exercise().bindings()).hasSize(3);

        ObjectNode insufficient = choice.deepCopy(); insufficient.withObject("exercise").withArray("bindings").remove(2);
        assertInvalid(insufficient);
        ObjectNode duplicate = choice.deepCopy();
        ((ObjectNode) duplicate.withObject("exercise").withArray("bindings").get(2))
                .put("bindingId", duplicate.path("exercise").path("bindings").get(1).path("bindingId").textValue());
        assertInvalid(duplicate);
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
