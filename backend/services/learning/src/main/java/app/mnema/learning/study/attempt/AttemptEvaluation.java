package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.exercise.MappingRules;
import app.mnema.learning.catalog.exercise.OrderEquivalence;
import app.mnema.learning.catalog.exercise.TextRule;
import app.mnema.learning.platform.api.InvalidRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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
        return evaluate(subject, response, true);
    }

    /**
     * The response must fit the issued presentation (kind, ids, blank and pair sets) before anything else:
     * a malformed response is a 400 that consumes nothing, even when the media or the evaluator is unavailable.
     * Only an explicit CANCEL bypasses that check and the media check.
     */
    static AttemptEvaluation evaluate(Subject subject, AttemptCommand.Response response, boolean mediaReady) {
        if (response instanceof AttemptCommand.CancelResponse) return notAssessed();
        requireShape(subject, response);
        if (!mediaReady) return mediaNotReady();
        JsonNode evaluator = subject.evaluator();
        // The semantic evaluator has no runtime: never fall back to exact matching, never blame the learner.
        if (!"1".equals(evaluator.path("version").asString(""))
                || !subject.type().evaluatorId().equals(evaluator.path("id").asString(""))) return unavailable();
        AnswerKey key = AnswerKey.parse(subject.type(), subject.answerKey());
        return switch (subject.type()) {
            case SELF_CHECK -> selfCheck((AttemptCommand.SelfCheckResponse) response);
            case FREE_RESPONSE -> freeResponse((AnswerKey.Text) key, subject, (AttemptCommand.TextResponse) response);
            case CLOZE -> cloze((AnswerKey.Cloze) key, subject, (AttemptCommand.ClozeResponse) response);
            case CHOICE -> choice((AnswerKey.Choice) key, subject, (AttemptCommand.ChoiceResponse) response);
            case MATCH -> match((AnswerKey.Match) key, subject, (AttemptCommand.MatchResponse) response);
            case ORDER -> order((AnswerKey.Order) key, subject, (AttemptCommand.OrderResponse) response);
            case CATEGORIZE -> categorize((AnswerKey.Categorize) key, subject,
                    (AttemptCommand.CategorizeResponse) response);
        };
    }

    /** @throws InvalidRequestException the response does not fit the issued presentation */
    static void requireShape(Subject subject, AttemptCommand.Response response) {
        boolean fits = switch (subject.type()) {
            case SELF_CHECK -> response instanceof AttemptCommand.SelfCheckResponse;
            // a transcript of speech is accepted only where the exercise takes speech
            case FREE_RESPONSE -> response instanceof AttemptCommand.TextResponse text
                    && (text.typed() || "TEXT_OR_SPEECH".equals(subject.content().path("responseInput").stringValue(null)));
            case CLOZE -> response instanceof AttemptCommand.ClozeResponse cloze && blanksMatch(subject, cloze);
            case CHOICE -> response instanceof AttemptCommand.ChoiceResponse choice && optionsMatch(subject, choice);
            case MATCH -> response instanceof AttemptCommand.MatchResponse match && pairsMatch(subject, match);
            case ORDER -> response instanceof AttemptCommand.OrderResponse order && permutationMatches(subject, order);
            case CATEGORIZE -> response instanceof AttemptCommand.CategorizeResponse categorize
                    && assignmentsMatch(subject, categorize);
        };
        if (!fits) throw new InvalidRequestException();
    }

    private static boolean blanksMatch(Subject subject, AttemptCommand.ClozeResponse cloze) {
        Set<UUID> issued = new HashSet<>();
        subject.content().path("passage").forEach(segment -> {
            if (segment.path("kind").stringValue(null).equals("BLANK")) {
                issued.add(UUID.fromString(segment.path("blankId").stringValue(null)));
            }
        });
        Set<UUID> supplied = new HashSet<>();
        cloze.blanks().forEach(blank -> supplied.add(blank.blankId()));
        return supplied.size() == cloze.blanks().size() && supplied.equals(issued);
    }

    private static boolean optionsMatch(Subject subject, AttemptCommand.ChoiceResponse choice) {
        Set<UUID> issued = new HashSet<>();
        subject.content().path("options")
                .forEach(option -> issued.add(UUID.fromString(option.path("optionId").stringValue(null))));
        Set<UUID> selected = new HashSet<>(choice.optionIds());
        boolean single = subject.content().path("selectionMode").stringValue(null).equals("SINGLE");
        return selected.size() == choice.optionIds().size() && issued.containsAll(selected)
                && (!single || selected.size() == 1);
    }

    /** An exact bijection of the issued ids: nothing missing, nothing foreign, nothing used twice. */
    private static boolean pairsMatch(Subject subject, AttemptCommand.MatchResponse match) {
        return MappingRules.bijection(
                match.pairs().stream().map(pair -> new MappingRules.Link(pair.leftId(), pair.rightId())).toList(),
                ids(subject.content().path("left"), "itemId"), ids(subject.content().path("right"), "itemId"));
    }

    /** An exact permutation of the issued item ids: nothing missing, nothing foreign, nothing repeated. */
    private static boolean permutationMatches(Subject subject, AttemptCommand.OrderResponse order) {
        Set<UUID> supplied = new HashSet<>(order.sequence());
        return supplied.size() == order.sequence().size() && supplied.equals(ids(subject.content().path("items"), "itemId"));
    }

    /** Every issued item exactly once, each to an issued category; categories may repeat or stay empty. */
    private static boolean assignmentsMatch(Subject subject, AttemptCommand.CategorizeResponse categorize) {
        return MappingRules.totalManyToOne(categorize.assignments().stream()
                        .map(assignment -> new MappingRules.Link(assignment.itemId(), assignment.categoryId())).toList(),
                ids(subject.content().path("items"), "itemId"), ids(subject.content().path("categories"), "categoryId"));
    }

    private static Set<UUID> ids(JsonNode array, String field) {
        Set<UUID> ids = new HashSet<>();
        array.forEach(node -> ids.add(UUID.fromString(node.path(field).stringValue(null))));
        return ids;
    }

    private static AttemptEvaluation selfCheck(AttemptCommand.SelfCheckResponse self) {
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
                                                  AttemptCommand.TextResponse text) {
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

    private static AttemptEvaluation cloze(AnswerKey.Cloze key, Subject subject, AttemptCommand.ClozeResponse cloze) {
        List<UUID> issued = new ArrayList<>();
        subject.content().path("passage").forEach(segment -> {
            if (segment.path("kind").stringValue(null).equals("BLANK")) {
                issued.add(UUID.fromString(segment.path("blankId").stringValue(null)));
            }
        });
        Map<UUID, String> supplied = new HashMap<>();
        cloze.blanks().forEach(blank -> supplied.put(blank.blankId(), blank.text()));
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

    private static AttemptEvaluation choice(AnswerKey.Choice key, Subject subject, AttemptCommand.ChoiceResponse choice) {
        Set<UUID> selected = new HashSet<>(choice.optionIds());
        Result result = selected.equals(new HashSet<>(key.correctOptionIds())) ? Result.CORRECT : Result.INCORRECT;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SERVER_ISSUED_OPTION").add("EXACT_OPTION_SET");
        ArrayNode correct = feedback.putArray("correctOptionIds");
        key.correctOptionIds().forEach(id -> correct.add(id.toString()));
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                List.of("RECOGNITION", "DETERMINISTIC",
                        subject.transcriptRevealed() ? "TRANSCRIPT_ACCOMMODATION" : "PRODUCTION"), feedback);
    }

    private static AttemptEvaluation match(AnswerKey.Match key, Subject subject, AttemptCommand.MatchResponse match) {
        Map<UUID, UUID> supplied = new HashMap<>();
        match.pairs().forEach(pair -> supplied.put(pair.leftId(), pair.rightId()));
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
     * Binary result on the equivalence-class sequence: swapping items whose blocks are identical never changes
     * it, so a correct order with interchangeable copies is not an error. Per-position correctness uses the same
     * comparison, so positions and result always agree.
     */
    private static AttemptEvaluation order(AnswerKey.Order key, Subject subject, AttemptCommand.OrderResponse order) {
        Map<UUID, String> signatures = OrderEquivalence.signatures(subject.content().path("items"));
        List<String> expected = OrderEquivalence.classes(key.sequence(), signatures);
        List<String> selected = OrderEquivalence.classes(order.sequence(), signatures);
        ArrayNode positions = JsonNodeFactory.instance.arrayNode();
        boolean all = true;
        for (int position = 0; position < expected.size(); position++) {
            boolean right = expected.get(position).equals(selected.get(position));
            all &= right;
            positions.addObject().put("position", position)
                    .put("selectedItemId", order.sequence().get(position).toString()).put("correct", right);
        }
        Result result = all ? Result.CORRECT : Result.INCORRECT;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SERVER_ISSUED_SEQUENCE").add("EXACT_SEQUENCE")
                .add("IDENTICAL_ITEMS_INTERCHANGEABLE");
        ArrayNode correct = feedback.putArray("correctSequence");
        key.sequence().forEach(id -> correct.add(id.toString()));
        feedback.set("positions", positions);
        List<String> reasons = new ArrayList<>(List.of("SEQUENCING", "DETERMINISTIC"));
        if (subject.transcriptRevealed()) reasons.add("TRANSCRIPT_ACCOMMODATION");
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.MEDIUM, List.copyOf(reasons), feedback);
    }

    private static AttemptEvaluation categorize(AnswerKey.Categorize key, Subject subject,
                                                AttemptCommand.CategorizeResponse categorize) {
        Map<UUID, UUID> supplied = new HashMap<>();
        categorize.assignments().forEach(assignment -> supplied.put(assignment.itemId(), assignment.categoryId()));
        ArrayNode assignments = JsonNodeFactory.instance.arrayNode();
        int correct = 0;
        for (AnswerKey.Assignment assignment : key.assignments()) {
            UUID selected = supplied.get(assignment.itemId());
            boolean right = selected.equals(assignment.categoryId());
            if (right) correct++;
            assignments.addObject().put("itemId", assignment.itemId().toString())
                    .put("selectedCategoryId", selected.toString())
                    .put("correctCategoryId", assignment.categoryId().toString()).put("correct", right);
        }
        Result result = correct == key.assignments().size() ? Result.CORRECT
                : correct == 0 ? Result.INCORRECT : Result.PARTIAL;
        ObjectNode feedback = JsonNodeFactory.instance.objectNode().put("result", result.name());
        feedback.putArray("appliedRules").add("SERVER_ISSUED_CATEGORY_MAP");
        feedback.set("assignments", assignments);
        return new AttemptEvaluation(Status.ASSESSED, result, EvidenceClass.LOW,
                List.of("CATEGORIZING", "DETERMINISTIC",
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
