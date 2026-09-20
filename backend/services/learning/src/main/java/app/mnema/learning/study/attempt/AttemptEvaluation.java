package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record AttemptEvaluation(Status status, Result result, EvidenceClass evidenceClass,
                         List<String> reasonCodes, ObjectNode feedback) {
    static AttemptEvaluation evaluate(String exerciseType, JsonNode evaluator, JsonNode answer,
                                      AttemptCommand command) {
        if (command.response() instanceof AttemptCommand.CancelResponse) {
            return notAssessed();
        }
        String evaluatorId = evaluator.path("id").asText();
        String evaluatorVersion = evaluator.path("version").asText();
        if (!evaluatorVersion.equals("1")) return unavailable();
        if (exerciseType.equals("TYPED") && evaluatorId.equals("deterministic-text")) {
            if (!(command.response() instanceof AttemptCommand.TextResponse text)) throw new InvalidRequestException();
            return typed(answer, text.text(), command.hintsUsed());
        }
        if (exerciseType.equals("SELF_CHECK") && evaluatorId.equals("self-check")) {
            if (!(command.response() instanceof AttemptCommand.SelfCheckResponse self)) {
                throw new InvalidRequestException();
            }
            return selfCheck(self.rating());
        }
        return unavailable();
    }

    private static AttemptEvaluation typed(JsonNode answer, String supplied, List<String> hints) {
        List<String> rules = new ArrayList<>();
        answer.path("normalization").forEach(rule -> rules.add(rule.textValue()));
        String normalized = normalize(supplied, rules);
        boolean correct = false;
        for (JsonNode accepted : answer.path("accepted")) {
            if (normalize(accepted.textValue(), rules).equals(normalized)) { correct = true; break; }
        }
        Result result = correct ? Result.CORRECT : Result.INCORRECT;
        EvidenceClass strength = correct && !hints.isEmpty() ? EvidenceClass.MEDIUM : EvidenceClass.HIGH;
        List<String> reasons = new ArrayList<>();
        reasons.add(hints.isEmpty() ? "UNHINTED" : "HINTED");
        reasons.add("DETERMINISTIC");
        reasons.add("PRODUCTION");
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name())
                .put("reference", answer.path("accepted").get(0).textValue());
        ArrayNode applied = feedback.putArray("appliedRules");
        rules.forEach(applied::add);
        return new AttemptEvaluation(Status.ASSESSED, result, strength, reasons, feedback);
    }

    private static AttemptEvaluation selfCheck(AttemptCommand.SelfRating rating) {
        Result result = switch (rating) {
            case FULL -> Result.CORRECT;
            case PARTIAL, HINTED -> Result.PARTIAL;
            case NOT_RECALLED -> Result.INCORRECT;
        };
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SELF_REPORT");
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                List.of("SELF_REPORT", rating.name()), feedback);
    }

    private static String normalize(String value, List<String> rules) {
        String result = value;
        for (String rule : rules) {
            result = switch (rule) {
                case "UNICODE_NFC" -> Normalizer.normalize(result, Normalizer.Form.NFC);
                case "TRIM" -> result.strip();
                case "CASE_FOLD" -> result.toLowerCase(Locale.ROOT);
                default -> throw new IllegalStateException("Unknown persisted normalization rule");
            };
        }
        return result;
    }

    private static AttemptEvaluation notAssessed() {
        return new AttemptEvaluation(Status.NOT_ASSESSED, null, null, List.of(),
                JsonNodeFactory.instance.objectNode().put("result", "NOT_ASSESSED"));
    }

    private static AttemptEvaluation unavailable() {
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", "UNAVAILABLE");
        feedback.putArray("reasonCodes").add("EVALUATOR_UNAVAILABLE");
        return new AttemptEvaluation(Status.UNAVAILABLE, null, null, List.of("EVALUATOR_UNAVAILABLE"), feedback);
    }

    enum Status { ASSESSED, NOT_ASSESSED, UNAVAILABLE }
    enum Result { CORRECT, PARTIAL, UNSURE, INCORRECT }
    enum EvidenceClass { HIGH, MEDIUM, LOW }
}
