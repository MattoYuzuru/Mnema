package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.exercise.TextRule;
import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, deterministic evaluation of one response against the pinned answer key. It never reads the
 * database, the clock or client-supplied authority: hints and transcript use come from server records.
 */
record AttemptEvaluation(Status status, Result result, EvidenceClass evidenceClass,
                         List<String> reasonCodes, ObjectNode feedback) {

    /**
     * Everything the evaluator may know about an issued presentation. {@code content} is the learner
     * content that was issued, so ids are validated against what the learner actually saw.
     */
    record Subject(ExerciseType type, JsonNode evaluator, JsonNode answerKey, JsonNode content, JsonNode reveal,
                   Set<UUID> hintedBlanks, boolean transcriptRevealed) {
        Subject { hintedBlanks = Set.copyOf(hintedBlanks); }
    }

    static AttemptEvaluation evaluate(Subject subject, AttemptCommand.Response response) {
        if (response instanceof AttemptCommand.CancelResponse) return notAssessed();
        JsonNode evaluator = subject.evaluator();
        // The semantic evaluator has no runtime: never fall back to exact matching, never blame the learner.
        if (!"1".equals(evaluator.path("version").asText())
                || !subject.type().evaluatorId().equals(evaluator.path("id").asText())) return unavailable();
        AnswerKey key = AnswerKey.parse(subject.type(), subject.answerKey());
        return switch (subject.type()) {
            case SELF_CHECK -> selfCheck(response);
            case FREE_RESPONSE -> freeResponse((AnswerKey.Text) key, subject, response);
            case CLOZE -> cloze((AnswerKey.Cloze) key, subject, response);
            case CHOICE -> choice((AnswerKey.Choice) key, subject, response);
            case MATCH -> match((AnswerKey.Match) key, subject, response);
        };
    }

    private static AttemptEvaluation selfCheck(AttemptCommand.Response response) {
        if (!(response instanceof AttemptCommand.SelfCheckResponse self)) throw new InvalidRequestException();
        Result result = switch (self.rating()) {
            case FULL -> Result.CORRECT;
            case PARTIAL, HINTED -> Result.PARTIAL;
            case NOT_RECALLED -> Result.INCORRECT;
        };
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SELF_REPORT");
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                List.of("SELF_REPORT", self.rating().name()), feedback);
    }

    private static AttemptEvaluation freeResponse(AnswerKey.Text key, Subject subject,
                                                  AttemptCommand.Response response) {
        if (!(response instanceof AttemptCommand.TextResponse text)) throw new InvalidRequestException();
        boolean correct = key.rule().matches(text.text());
        Result result = correct ? Result.CORRECT : Result.INCORRECT;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        rules(feedback, key.rule().appliedRules());
        feedback.put("reference", key.rule().accepted().getFirst());
        feedback.set("referenceContent", subject.reveal().path("reference").deepCopy());
        return new AttemptEvaluation(Status.ASSESSED, result,
                subject.transcriptRevealed() ? EvidenceClass.LOW : EvidenceClass.HIGH,
                List.of(subject.transcriptRevealed() ? "TRANSCRIPT_ACCOMMODATION" : "UNHINTED", "DETERMINISTIC",
                        "PRODUCTION"), feedback);
    }

    private static AttemptEvaluation cloze(AnswerKey.Cloze key, Subject subject, AttemptCommand.Response response) {
        if (!(response instanceof AttemptCommand.ClozeResponse cloze)) throw new InvalidRequestException();
        List<UUID> issued = new ArrayList<>();
        subject.content().path("passage").forEach(segment -> {
            if (segment.path("kind").textValue().equals("BLANK")) {
                issued.add(UUID.fromString(segment.path("blankId").textValue()));
            }
        });
        Map<UUID, String> supplied = new HashMap<>();
        cloze.blanks().forEach(blank -> supplied.put(blank.blankId(), blank.text()));
        if (supplied.size() != cloze.blanks().size() || !supplied.keySet().equals(new HashSet<>(issued))) {
            throw new InvalidRequestException();
        }
        Map<UUID, TextRule> rules = new HashMap<>();
        key.blanks().forEach(blank -> rules.put(blank.blankId(), blank.rule()));
        ArrayNode blanks = JsonNodeFactory.instance.arrayNode();
        Set<String> applied = new LinkedHashSet<>();
        applied.add("PER_BLANK");
        int correct = 0;
        for (UUID blankId : issued) {
            TextRule rule = rules.get(blankId);
            boolean right = rule.matches(supplied.get(blankId));
            if (right) correct++;
            applied.addAll(rule.appliedRules());
            blanks.addObject().put("blankId", blankId.toString()).put("correct", right)
                    .put("hinted", subject.hintedBlanks().contains(blankId))
                    .put("reference", rule.accepted().getFirst());
        }
        Result result = correct == issued.size() ? Result.CORRECT : correct == 0 ? Result.INCORRECT : Result.PARTIAL;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        rules(feedback, List.copyOf(applied));
        feedback.set("blanks", blanks);
        boolean hinted = !subject.hintedBlanks().isEmpty();
        EvidenceClass strength = subject.transcriptRevealed() ? EvidenceClass.LOW
                : result != Result.INCORRECT && hinted ? EvidenceClass.MEDIUM : EvidenceClass.HIGH;
        return new AttemptEvaluation(Status.ASSESSED, result, strength,
                List.of(subject.transcriptRevealed() ? "TRANSCRIPT_ACCOMMODATION" : hinted ? "HINTED" : "UNHINTED",
                        "DETERMINISTIC", "PRODUCTION"), feedback);
    }

    private static AttemptEvaluation choice(AnswerKey.Choice key, Subject subject, AttemptCommand.Response response) {
        if (!(response instanceof AttemptCommand.ChoiceResponse choice)) throw new InvalidRequestException();
        Set<UUID> issued = new HashSet<>();
        subject.content().path("options")
                .forEach(option -> issued.add(UUID.fromString(option.path("optionId").textValue())));
        Set<UUID> selected = new HashSet<>(choice.optionIds());
        boolean single = subject.content().path("selectionMode").textValue().equals("SINGLE");
        if (selected.size() != choice.optionIds().size() || !issued.containsAll(selected)
                || (single && selected.size() != 1)) throw new InvalidRequestException();
        Result result = selected.equals(new HashSet<>(key.correctOptionIds())) ? Result.CORRECT : Result.INCORRECT;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SERVER_ISSUED_OPTION").add("EXACT_OPTION_SET");
        ArrayNode correct = feedback.putArray("correctOptionIds");
        key.correctOptionIds().forEach(id -> correct.add(id.toString()));
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                List.of("RECOGNITION", "DETERMINISTIC",
                        subject.transcriptRevealed() ? "TRANSCRIPT_ACCOMMODATION" : "PRODUCTION"), feedback);
    }

    private static AttemptEvaluation match(AnswerKey.Match key, Subject subject, AttemptCommand.Response response) {
        if (!(response instanceof AttemptCommand.MatchResponse match)) throw new InvalidRequestException();
        Set<UUID> lefts = new HashSet<>();
        Set<UUID> rights = new HashSet<>();
        subject.content().path("left").forEach(item -> lefts.add(UUID.fromString(item.path("itemId").textValue())));
        subject.content().path("right").forEach(item -> rights.add(UUID.fromString(item.path("itemId").textValue())));
        Map<UUID, UUID> supplied = new HashMap<>();
        match.pairs().forEach(pair -> supplied.put(pair.leftId(), pair.rightId()));
        // An exact bijection of the issued ids: nothing missing, nothing foreign, nothing used twice.
        if (supplied.size() != match.pairs().size() || !supplied.keySet().equals(lefts)
                || !new HashSet<>(supplied.values()).equals(rights)) throw new InvalidRequestException();
        ArrayNode pairs = JsonNodeFactory.instance.arrayNode();
        int correct = 0;
        for (AnswerKey.Pair pair : key.pairs()) {
            UUID selected = supplied.get(pair.leftId());
            boolean right = selected.equals(pair.rightId());
            if (right) correct++;
            pairs.addObject().put("leftId", pair.leftId().toString()).put("selectedRightId", selected.toString())
                    .put("correctRightId", pair.rightId().toString()).put("correct", right);
        }
        Result result = correct == key.pairs().size() ? Result.CORRECT
                : correct == 0 ? Result.INCORRECT : Result.PARTIAL;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SERVER_ISSUED_PAIR_MAP");
        feedback.set("pairs", pairs);
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                List.of("MATCHING", "DETERMINISTIC",
                        subject.transcriptRevealed() ? "TRANSCRIPT_ACCOMMODATION" : "RECOGNITION"), feedback);
    }

    /**
     * A MATCH assembled correctly only after a recorded wrong pair-check is partial evidence: the learner
     * can find the mapping by elimination.
     */
    AttemptEvaluation withPairRetry() {
        ObjectNode adjusted = feedback.deepCopy().put("result", Result.PARTIAL.name());
        adjusted.withArray("appliedRules").add("PAIR_RETRY");
        List<String> reasons = new ArrayList<>(reasonCodes);
        reasons.add("PAIR_RETRY");
        return new AttemptEvaluation(status, Result.PARTIAL, evidenceClass, List.copyOf(reasons), adjusted);
    }

    private static void rules(ObjectNode feedback, List<String> rules) {
        ArrayNode applied = feedback.putArray("appliedRules");
        rules.forEach(applied::add);
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
