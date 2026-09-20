package app.mnema.learning.study.attempt;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptEvaluationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ObjectNode evaluator = JSON.createObjectNode().put("id", "deterministic-text").put("version", "1");
    private final ObjectNode answer = answer();

    @Test
    void typedNormalizationHintsAndConfidenceKeepResultSeparateFromEvidenceClass() {
        AttemptEvaluation unhinted = AttemptEvaluation.evaluate("TYPED", evaluator, answer,
                command("  MÉMOIRE  ", List.of(), "KNEW"));
        AttemptEvaluation hinted = AttemptEvaluation.evaluate("TYPED", evaluator, answer,
                command("mémoire", List.of("REVEAL_FIRST_GRAPHEME"), "GUESSED"));
        AttemptEvaluation incorrect = AttemptEvaluation.evaluate("TYPED", evaluator, answer,
                command("wrong", List.of("REVEAL_FIRST_GRAPHEME"), "KNEW"));

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
        AttemptEvaluation cancel = AttemptEvaluation.evaluate("TYPED", evaluator, answer, cancelled);
        AttemptEvaluation unavailable = AttemptEvaluation.evaluate("CLOZE_SINGLE", evaluator, answer,
                command("mémoire", List.of(), null));

        assertThat(cancel.status()).isEqualTo(AttemptEvaluation.Status.NOT_ASSESSED);
        assertThat(cancel.result()).isNull();
        assertThat(unavailable.status()).isEqualTo(AttemptEvaluation.Status.UNAVAILABLE);
        assertThat(unavailable.result()).isNull();
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
