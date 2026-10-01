package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static app.mnema.learning.study.attempt.PreviewRequests.exercise;
import static app.mnema.learning.study.attempt.PreviewRequests.hint;
import static app.mnema.learning.study.attempt.PreviewRequests.mechanicExercise;
import static app.mnema.learning.study.attempt.PreviewRequests.pairCheck;
import static app.mnema.learning.study.attempt.PreviewRequests.previewFixture;
import static app.mnema.learning.study.attempt.PreviewRequests.request;
import static app.mnema.learning.support.ContractFixtures.JSON;
import static app.mnema.learning.support.ContractFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExercisePreviewCommandTest {
    private static final String BLANK_1 = "b1a00000-0000-4000-8000-000000000001";

    @Test
    void readsEveryFixtureActionWithItsValidatedDefinition() {
        ExercisePreviewCommand cloze = ExercisePreviewCommand.read(bytes(previewFixture("submitCloze")));
        assertThat(cloze.definition().type()).isEqualTo(ExerciseType.CLOZE);
        assertThat(cloze.action()).isInstanceOfSatisfying(ExercisePreviewCommand.Submit.class, submit -> {
            assertThat(submit.hintedBlankIds()).containsExactly(UUID.fromString("b1a00000-0000-4000-8000-000000000003"));
            assertThat(submit.pairMistakes()).isFalse();
            assertThat(submit.transcriptRevealed()).isFalse();
        });
        assertThat(ExercisePreviewCommand.read(bytes(previewFixture("submitChoice"))).definition().type())
                .isEqualTo(ExerciseType.CHOICE);
        assertThat(ExercisePreviewCommand.read(bytes(previewFixture("submitMatchAfterMistake"))).action())
                .isInstanceOfSatisfying(ExercisePreviewCommand.Submit.class,
                        submit -> assertThat(submit.pairMistakes()).isTrue());
        assertThat(ExercisePreviewCommand.read(bytes(previewFixture("submitFreeResponse"))).definition().type())
                .isEqualTo(ExerciseType.FREE_RESPONSE);
        assertThat(ExercisePreviewCommand.read(bytes(previewFixture("pairCheck"))).action())
                .isInstanceOf(ExercisePreviewCommand.PairCheck.class);
        assertThat(ExercisePreviewCommand.read(bytes(previewFixture("hint"))).action())
                .isInstanceOfSatisfying(ExercisePreviewCommand.Hint.class,
                        hint -> assertThat(hint.blankId().toString()).isEqualTo(BLANK_1));
    }

    @Test
    void validatesTheExerciseExactlyLikePublicationDoesWithoutResolvingAnything() {
        // MATERIAL blocks and media assets are opaque: the match fixture holds a MATERIAL block and four assets.
        ObjectNode match = previewFixture("submitMatchAfterMistake");
        assertThat(match.toString()).contains("MATERIAL", "VIDEO", "AUDIO");
        assertThat(ExercisePreviewCommand.read(bytes(match)).definition().type()).isEqualTo(ExerciseType.MATCH);
        // the five mechanics and the structurally valid semantic evaluator all pass
        for (String name : new String[] {"createSelfCheck", "createFreeResponseAudio", "createCloze",
                "createChoiceVideoMultiple", "createMatchMixed", "rejectedAiAssessment"}) {
            ObjectNode exercise = mechanicExercise(name);
            assertThat(ExercisePreviewCommand.read(bytes(request(exercise, hint(BLANK_1)))).definition()).isNotNull();
        }
    }

    @Test
    void rejectsEveryStructuralViolationOpaquely() {
        for (Consumer<ObjectNode> corrupt : List.<Consumer<ObjectNode>>of(
                body -> body.put("extra", true),
                body -> body.remove("action"),
                body -> body.remove("exercise"),
                body -> exercise(body).put("extra", true),
                body -> exercise(body).put("enabled", true),
                body -> exercise(body).putObject("subject"),
                body -> exercise(body).remove("evaluatorPolicy"),
                body -> exercise(body).put("schemaVersion", 1),
                body -> exercise(body).put("schemaVersion", "2"),
                body -> exercise(body).put("schemaVersion", 2.5),
                body -> exercise(body).put("schemaVersion", 4_294_967_298L),
                body -> exercise(body).put("type", "CLOZE_SINGLE"),
                body -> exercise(body).put("type", "choice"),
                body -> exercise(body).putNull("type"),
                body -> exercise(body).withObject("answerKey").put("kind", "TEXT"),
                body -> exercise(body).withObject("content").put("extra", 1),
                body -> exercise(body).withObject("evaluatorPolicy").put("id", "deterministic-text"),
                body -> exercise(body).withObject("evaluatorPolicy").put("version", "2"),
                body -> exercise(body).withObject("content").withArray("passage").removeAll(),
                body -> body.putObject("exercise"),
                body -> body.put("exercise", "x"))) {
            ObjectNode body = previewFixture("submitCloze");
            corrupt.accept(body);
            assertInvalid(body);
        }
    }

    @Test
    void rejectsContentThatPublicationRejects() {
        // a key that disagrees with the content, an asset pinned as two media kinds and an over-long block
        ObjectNode mismatch = previewFixture("submitCloze");
        exercise(mismatch).withObject("answerKey").withArray("blanks").remove(0);
        assertInvalid(mismatch);

        ObjectNode asset = previewFixture("submitChoice");
        ((ObjectNode) exercise(asset).withObject("content").withArray("options").get(2).withArray("blocks").get(0))
                .put("assetId", "aaaaaaaa-0000-4000-8000-000000000004");
        assertInvalid(asset);

        ObjectNode tooLong = previewFixture("submitFreeResponse");
        ((ObjectNode) exercise(tooLong).withObject("content").withArray("prompt").get(1)).put("text", "x".repeat(4_001));
        assertInvalid(tooLong);
    }

    @Test
    void rejectsMalformedActions() {
        ObjectNode submit = previewFixture("submitCloze");
        for (Consumer<ObjectNode> corrupt : List.<Consumer<ObjectNode>>of(
                body -> action(body).put("kind", "CANCEL"),
                body -> action(body).put("kind", "submit"),
                body -> action(body).putNull("kind"),
                body -> action(body).put("extra", 1),
                body -> action(body).remove("hintedBlankIds"),
                body -> action(body).remove("pairMistakes"),
                body -> action(body).put("pairMistakes", "false"),
                body -> action(body).remove("transcriptRevealed"),
                body -> action(body).put("transcriptRevealed", 0),
                body -> action(body).put("hintedBlankIds", "x"),
                body -> action(body).withArray("hintedBlankIds").add("not-an-id"),
                body -> action(body).withArray("hintedBlankIds").add(BLANK_1).add(BLANK_1),
                body -> {
                    var ids = action(body).withArray("hintedBlankIds");
                    ids.removeAll();
                    for (int index = 0; index < 13; index++) ids.add(UUID.randomUUID().toString());
                },
                body -> action(body).withObject("response").put("kind", "CANCEL"),
                body -> action(body).putObject("response").put("kind", "NOPE"),
                body -> action(body).withObject("response").put("correctAnswer", "map"),
                body -> action(body).put("response", "map"))) {
            ObjectNode body = submit.deepCopy();
            corrupt.accept(body);
            assertInvalid(body);
        }
        ObjectNode exercise = exercise(previewFixture("submitMatchAfterMistake"));
        for (ObjectNode action : new ObjectNode[] {
                pairCheck("1e000000-0000-4000-8000-000000000001", "bad"),
                pairCheck("bad", "7e000000-0000-4000-8000-000000000001"),
                pairCheck("1E000000-0000-4000-8000-000000000001", "7e000000-0000-4000-8000-000000000001"),
                (ObjectNode) pairCheck("1e000000-0000-4000-8000-000000000001",
                        "7e000000-0000-4000-8000-000000000001").put("presentationId", "x"),
                hint("bad"), (ObjectNode) hint(BLANK_1).put("nonce", "n"),
                (ObjectNode) JSON.createObjectNode().put("kind", "RESET")}) {
            assertInvalid(request(exercise.deepCopy(), action));
        }
    }

    @Test
    void rejectsMalformedJsonOversizeBodiesAndNonObjects() {
        for (String body : new String[] {"", "[]", "null", "\"x\"", "{", "{\"exercise\":{},\"exercise\":{}}",
                "{\"exercise\":{},\"action\":{}} trailing", "\u0000"}) {
            assertThatThrownBy(() -> ExercisePreviewCommand.read(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))).isInstanceOf(InvalidRequestException.class);
        }
        ObjectNode atLimit = previewFixture("submitFreeResponse");
        String padded = atLimit.toString();
        String oversize = padded.substring(0, padded.length() - 1) + ",\"x\":\""
                + "y".repeat(ExercisePreviewCommand.MAX_BYTES) + "\"}";
        assertThat(oversize.length()).isGreaterThan(ExercisePreviewCommand.MAX_BYTES);
        assertThatThrownBy(() -> ExercisePreviewCommand.read(
                new ByteArrayInputStream(oversize.getBytes(StandardCharsets.UTF_8)))).isInstanceOf(InvalidRequestException.class);
        // an invalid-UTF-8 body is as opaque as any other
        assertThatThrownBy(() -> ExercisePreviewCommand.read(new ByteArrayInputStream(new byte[] {'{', (byte) 0xC3, '}'})))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theParsedCommandIsDefensivelyCopied() {
        ExercisePreviewCommand command = ExercisePreviewCommand.read(bytes(previewFixture("submitCloze")));
        command.content().removeAll();
        command.answerKey().removeAll();
        command.evaluatorPolicy().removeAll();
        assertThat(command.content().isEmpty()).isFalse();
        assertThat(command.answerKey().isEmpty()).isFalse();
        assertThat(command.evaluatorPolicy().isEmpty()).isFalse();
    }

    private static ObjectNode exercise(ObjectNode body) { return body.withObject("exercise"); }

    private static ObjectNode action(ObjectNode body) { return body.withObject("action"); }

    private static void assertInvalid(JsonNode body) {
        assertThatThrownBy(() -> ExercisePreviewCommand.read(bytes(body))).isInstanceOf(InvalidRequestException.class);
    }
}
