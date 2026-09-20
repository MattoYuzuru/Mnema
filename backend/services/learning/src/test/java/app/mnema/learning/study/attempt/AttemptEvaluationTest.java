package app.mnema.learning.study.attempt;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptEvaluationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ObjectNode evaluator = JSON.createObjectNode().put("id", "deterministic-text").put("version", "1");
    private final ObjectNode answer = answer();

    @Test
    void typedNormalizationHintsAndConfidenceKeepResultSeparateFromEvidenceClass() {
        AttemptEvaluation unhinted = AttemptEvaluation.evaluate("TYPED", evaluator, answer,
                bindings(), command("  MÉMOIRE  ", List.of(), "KNEW"));
        AttemptEvaluation hinted = AttemptEvaluation.evaluate("TYPED", evaluator, answer,
                bindings(), command("mémoire", List.of("REVEAL_FIRST_GRAPHEME"), "GUESSED"));
        AttemptEvaluation incorrect = AttemptEvaluation.evaluate("TYPED", evaluator, answer,
                bindings(), command("wrong", List.of("REVEAL_FIRST_GRAPHEME"), "KNEW"));

        assertThat(unhinted.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(unhinted.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        assertThat(hinted.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(hinted.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.MEDIUM);
        assertThat(incorrect.result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(incorrect.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
    }

    @Test
    void cancelAndUnsupportedEvaluatorNeverBecomeIncorrectEvidence() {
        AttemptCommand cancelled = new AttemptCommand(UUID.randomUUID(), UUID.randomUUID(), "1234567890123456",
                new AttemptCommand.CancelResponse(), List.of(), null, 0, JSON.createObjectNode());
        AttemptEvaluation cancel = AttemptEvaluation.evaluate("TYPED", evaluator, answer, bindings(), cancelled);
        AttemptEvaluation unavailable = AttemptEvaluation.evaluate("CLOZE_SINGLE",
                JSON.createObjectNode().put("id", "other").put("version", "1"), answer, bindings(),
                command("mémoire", List.of(), null));

        assertThat(cancel.status()).isEqualTo(AttemptEvaluation.Status.NOT_ASSESSED);
        assertThat(cancel.result()).isNull();
        assertThat(unavailable.status()).isEqualTo(AttemptEvaluation.Status.UNAVAILABLE);
        assertThat(unavailable.result()).isNull();
    }

    @Test
    void clozeKeepsUnhintedProductionHighAndCapsOnlyHintedSuccess() {
        AttemptEvaluation unhinted = AttemptEvaluation.evaluate("CLOZE_SINGLE", evaluator, answer, bindings(),
                command("mémoire", List.of(), null));
        AttemptEvaluation hinted = AttemptEvaluation.evaluate("CLOZE_SINGLE", evaluator, answer, bindings(),
                command("mémoire", List.of("REVEAL_FIRST_GRAPHEME"), null));
        AttemptEvaluation blank = AttemptEvaluation.evaluate("CLOZE_SINGLE", evaluator, answer, bindings(),
                command("", List.of(), null));

        assertThat(unhinted.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(unhinted.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        assertThat(hinted.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.MEDIUM);
        assertThat(blank.result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(blank.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        assertThat(unhinted.feedback().path("appliedRules").get(0).textValue()).isEqualTo("SINGLE_BLANK");
    }

    @Test
    void choiceAcceptsOnlyIssuedOptionsAndNeverCreditsDistractorBindings() {
        UUID answerOption = UUID.randomUUID();
        UUID distractorOption = UUID.randomUUID();
        var bindings = choiceBindings(answerOption, distractorOption);
        ObjectNode choiceEvaluator = JSON.createObjectNode().put("id", "deterministic-choice").put("version", "1");

        AttemptEvaluation correct = AttemptEvaluation.evaluate("SINGLE_CHOICE", choiceEvaluator, answer, bindings,
                choiceCommand(answerOption));
        AttemptEvaluation wrong = AttemptEvaluation.evaluate("SINGLE_CHOICE", choiceEvaluator, answer, bindings,
                choiceCommand(distractorOption));

        assertThat(correct.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(correct.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
        assertThat(wrong.result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(wrong.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
        assertThatThrownBy(() -> AttemptEvaluation.evaluate("SINGLE_CHOICE", choiceEvaluator, answer, bindings,
                choiceCommand(UUID.randomUUID()))).isInstanceOf(app.mnema.learning.platform.api.InvalidRequestException.class);
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode bindings() {
        return JSON.createArrayNode();
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode choiceBindings(UUID answerOption, UUID distractor) {
        UUID member = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID answerNode = UUID.randomUUID();
        var values = JSON.createArrayNode();
        values.add(binding(UUID.randomUUID(), "ASSESSED", member, revision, answerNode));
        values.add(binding(answerOption, "OPTION", member, revision, answerNode));
        values.add(binding(distractor, "OPTION", member, revision, UUID.randomUUID()));
        return values;
    }

    private static ObjectNode binding(UUID id, String role, UUID member, UUID revision, UUID node) {
        ObjectNode value = JSON.createObjectNode().put("bindingId", id.toString()).put("role", role)
                .put("memberKey", member.toString()).put("itemRevisionId", revision.toString());
        value.putArray("nodeIds").add(node.toString());
        return value;
    }

    private static AttemptCommand choiceCommand(UUID option) {
        return new AttemptCommand(UUID.randomUUID(), UUID.randomUUID(), "1234567890123456",
                new AttemptCommand.ChoiceResponse(option), List.of(), null, 100, JSON.createObjectNode());
    }

    private AttemptCommand command(String text, List<String> hints, String confidence) {
        return new AttemptCommand(UUID.randomUUID(), UUID.randomUUID(), "1234567890123456",
                new AttemptCommand.TextResponse(text), hints, confidence, 100, JSON.createObjectNode());
    }

    private static ObjectNode answer() {
        ObjectNode value = JSON.createObjectNode().put("schemaVersion", 1);
        value.putArray("normalization").add("UNICODE_NFC").add("TRIM").add("CASE_FOLD");
        value.putArray("accepted").add("mémoire");
        return value;
    }
}
