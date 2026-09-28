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
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

record AttemptEvaluation(Status status, Result result, EvidenceClass evidenceClass,
                         List<String> reasonCodes, ObjectNode feedback) {
    static AttemptEvaluation evaluate(String exerciseType, JsonNode evaluator, JsonNode answer,
                                      JsonNode bindings, AttemptCommand command) {
        return evaluate(exerciseType, evaluator, answer, bindings, command, false);
    }

    static AttemptEvaluation evaluate(String exerciseType, JsonNode evaluator, JsonNode answer,
                                      JsonNode bindings, AttemptCommand command, boolean transcriptRevealed) {
        if (command.response() instanceof AttemptCommand.CancelResponse) {
            return notAssessed();
        }
        String evaluatorId = evaluator.path("id").asText();
        String evaluatorVersion = evaluator.path("version").asText();
        if (!evaluatorVersion.equals("1")) return unavailable();
        if ((exerciseType.equals("TYPED") || exerciseType.equals("CLOZE_SINGLE")
                || exerciseType.equals("LISTEN_TYPE"))
                && evaluatorId.equals("deterministic-text")) {
            if (!(command.response() instanceof AttemptCommand.TextResponse text)) throw new InvalidRequestException();
            return text(answer, text.text(), command.hintsUsed(), exerciseType.equals("CLOZE_SINGLE"),
                    exerciseType.equals("LISTEN_TYPE"), transcriptRevealed);
        }
        if (exerciseType.equals("SELF_CHECK") && evaluatorId.equals("self-check")) {
            if (!(command.response() instanceof AttemptCommand.SelfCheckResponse self)) {
                throw new InvalidRequestException();
            }
            return selfCheck(self.rating());
        }
        if ((exerciseType.equals("SINGLE_CHOICE") || exerciseType.equals("LISTEN_CHOICE"))
                && evaluatorId.equals("deterministic-choice")) {
            if (!(command.response() instanceof AttemptCommand.ChoiceResponse choice)
                    || !command.hintsUsed().isEmpty()) throw new InvalidRequestException();
            return choice(answer, bindings, choice.optionId(), transcriptRevealed);
        }
        if (exerciseType.equals("AUDIO_TEXT_MATCH") && evaluatorId.equals("deterministic-audio-match")) {
            if (!(command.response() instanceof AttemptCommand.MatchResponse match)
                    || !command.hintsUsed().isEmpty()) throw new InvalidRequestException();
            return match(answer, bindings, match.pairs(), transcriptRevealed);
        }
        return unavailable();
    }

    private static AttemptEvaluation text(JsonNode answer, String supplied, List<String> hints,
                                          boolean cloze, boolean listening, boolean transcriptRevealed) {
        Set<String> allowedHints = Set.of("REVEAL_FIRST_GRAPHEME");
        if ((listening && !hints.isEmpty()) || (!listening && !allowedHints.containsAll(hints))) {
            throw new InvalidRequestException();
        }
        List<String> rules = new ArrayList<>();
        answer.path("normalization").forEach(rule -> rules.add(rule.textValue()));
        String normalized = normalize(supplied, rules);
        boolean correct = false;
        for (JsonNode accepted : answer.path("accepted")) {
            if (normalize(accepted.textValue(), rules).equals(normalized)) { correct = true; break; }
        }
        Result result = correct ? Result.CORRECT : Result.INCORRECT;
        EvidenceClass strength = listening && transcriptRevealed ? EvidenceClass.LOW
                : correct && !hints.isEmpty() ? EvidenceClass.MEDIUM : EvidenceClass.HIGH;
        List<String> reasons = new ArrayList<>();
        reasons.add(transcriptRevealed ? "TRANSCRIPT_ACCOMMODATION" : hints.isEmpty() ? "UNHINTED" : "HINTED");
        reasons.add("DETERMINISTIC");
        reasons.add("PRODUCTION");
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name())
                .put("reference", answer.path("accepted").get(0).textValue());
        ArrayNode applied = feedback.putArray("appliedRules");
        if (listening) applied.add("AUDIO_CUE_V1");
        if (cloze) applied.add("SINGLE_BLANK");
        rules.forEach(applied::add);
        return new AttemptEvaluation(Status.ASSESSED, result, strength, reasons, feedback);
    }

    private static AttemptEvaluation choice(JsonNode answer, JsonNode bindings, UUID selectedOption,
                                            boolean transcriptRevealed) {
        JsonNode assessed = null;
        JsonNode selected = null;
        for (JsonNode binding : bindings) {
            if (binding.path("role").textValue().equals("ASSESSED")) assessed = binding;
            if (binding.path("role").textValue().equals("OPTION")
                    && binding.path("bindingId").textValue().equals(selectedOption.toString())) selected = binding;
        }
        if (assessed == null || selected == null) throw new InvalidRequestException();
        boolean correct = sameTarget(assessed, selected);
        Result result = correct ? Result.CORRECT : Result.INCORRECT;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name())
                .put("reference", answer.path("accepted").get(0).textValue());
        feedback.putArray("appliedRules").add("SERVER_ISSUED_OPTION");
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                transcriptRevealed ? List.of("RECOGNITION", "DETERMINISTIC", "TRANSCRIPT_ACCOMMODATION")
                        : List.of("RECOGNITION", "DETERMINISTIC", "PRODUCTION"), feedback);
    }

    private static AttemptEvaluation match(JsonNode answer, JsonNode bindings, List<AttemptCommand.MatchPair> supplied,
                                           boolean transcriptRevealed) {
        if (answer.path("schemaVersion").intValue() != 2) return unavailable();
        Map<UUID, UUID> expected = new HashMap<>();
        answer.path("pairs").forEach(pair -> expected.put(UUID.fromString(pair.path("cueId").textValue()),
                UUID.fromString(pair.path("optionId").textValue())));
        Set<UUID> issuedOptions = new HashSet<>();
        bindings.forEach(binding -> {
            if (binding.path("role").asText().equals("OPTION")) {
                issuedOptions.add(UUID.fromString(binding.path("bindingId").textValue()));
            }
        });
        if (expected.size() < 2 || expected.size() > 6 || supplied.size() != expected.size()
                || !issuedOptions.equals(new HashSet<>(expected.values()))) return unavailable();
        Map<UUID, UUID> response = new HashMap<>();
        supplied.forEach(pair -> response.put(pair.cueId(), pair.optionId()));
        if (!response.keySet().equals(expected.keySet()) || !issuedOptions.equals(new HashSet<>(response.values()))) {
            throw new InvalidRequestException();
        }
        ObjectNode feedback = JsonNodeFactory.instance.objectNode();
        ArrayNode pairResults = feedback.putArray("pairResults");
        int correct = 0;
        for (JsonNode pair : answer.path("pairs")) {
            UUID cue = UUID.fromString(pair.path("cueId").textValue());
            UUID selected = response.get(cue);
            UUID target = expected.get(cue);
            boolean right = selected.equals(target);
            if (right) correct++;
            pairResults.addObject().put("cueId", cue.toString())
                    .put("selectedOptionId", selected.toString()).put("correctOptionId", target.toString())
                    .put("correct", right);
        }
        Result result = correct == expected.size() ? Result.CORRECT : correct == 0 ? Result.INCORRECT : Result.PARTIAL;
        feedback.put("result", result.name());
        feedback.putArray("appliedRules").add("SERVER_ISSUED_PAIR_MAP");
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                transcriptRevealed ? List.of("MATCHING", "DETERMINISTIC", "TRANSCRIPT_ACCOMMODATION")
                        : List.of("MATCHING", "DETERMINISTIC", "RECOGNITION"), feedback);
    }

    private static boolean sameTarget(JsonNode left, JsonNode right) {
        return left.path("memberKey").equals(right.path("memberKey"))
                && left.path("itemRevisionId").equals(right.path("itemRevisionId"))
                && left.path("nodeIds").equals(right.path("nodeIds"));
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

    static AttemptEvaluation mediaNotReady() {
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", "NOT_ASSESSED");
        feedback.putArray("reasonCodes").add("MEDIA_NOT_READY");
        return new AttemptEvaluation(Status.NOT_ASSESSED, null, null, List.of("MEDIA_NOT_READY"), feedback);
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
