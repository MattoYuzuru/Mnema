package app.mnema.learning.generation.exercise;

import app.mnema.learning.catalog.exercise.TextRule;
import app.mnema.learning.platform.text.SoftTextNormalizer;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static app.mnema.learning.generation.exercise.ExerciseCode.*;

/**
 * The semantic lint of one schema-valid exercise ({@code contracts/generation/exercises/lint.json}, phase LINT): reference
 * resolution, the allowed mechanics and the per-mechanic table of architecture section 6. Pure and Spring-free. A rule whose
 * prerequisite failed is skipped (for example the fragment of a {@code CLOZE} needs a key that covers its blanks), so one
 * mistake is reported once. Findings carry the code and the member path, never content.
 */
final class ExerciseLint {
    /** Phrases of "all/none of the above" per output language (Russian and English); matched as whole words after normalization. */
    private static final List<String> ALL_OR_NONE = List.of("все перечисленные", "все вышеперечисленные", "все из перечисленных",
            "все из перечисленного", "все вышеуказанные", "всё перечисленное", "всё вышеперечисленное", "ни один из перечисленных",
            "ни одно из перечисленных", "ни один из вышеперечисленных", "ничего из перечисленного", "ничего из вышеперечисленного",
            "верны все варианты", "все ответы верны", "все варианты верны", "нет верного ответа", "all of the above",
            "none of the above", "all the above", "none the above", "all of these", "none of these", "both of the above",
            "all answers are correct");
    private static final List<String> DEFAULT_NORMALIZATION = List.of("UNICODE_NFC", "TRIM", "CASE_FOLD");

    private final JsonNode exercise;
    private final ExerciseContext context;
    private final List<ExerciseFinding> findings = new ArrayList<>();

    private ExerciseLint(JsonNode exercise, ExerciseContext context) {
        this.exercise = exercise;
        this.context = context;
    }

    static List<ExerciseFinding> lint(JsonNode exercise, ExerciseContext context) {
        ExerciseLint lint = new ExerciseLint(exercise, context);
        lint.run();
        return List.copyOf(new LinkedHashSet<>(lint.findings));
    }

    private void run() {
        String mechanic = exercise.path("mechanic").stringValue("");
        if (!context.allowedMechanics().contains(mechanic)) add(MECHANIC_NOT_ALLOWED, "mechanic");
        if (!context.materials().containsKey(exercise.path("subject").stringValue(""))) add(REF_UNKNOWN_HANDLE, "subject");
        JsonNode objective = exercise.path("objective");
        if (objective.has("ref") && !context.objectives().containsKey(objective.path("ref").stringValue(""))) {
            add(REF_UNKNOWN_OBJECTIVE, "objective.ref");
        }
        boolean resolved = references("prompt") & references("reference");
        switch (mechanic) {
            case "SELF_CHECK" -> selfCheck(resolved);
            case "FREE_RESPONSE" -> freeResponse(resolved);
            case "CLOZE" -> cloze();
            case "CHOICE" -> choice();
            case "MATCH" -> match();
            case "ORDER" -> order();
            case "CATEGORIZE" -> categorize();
            default -> { }
        }
    }

    private void add(ExerciseCode code, String path) {
        findings.add(ExerciseFinding.of(code, path));
    }

    // ------------------------------------------------------------------- blocks and references

    /** Every MATERIAL block of the slot must name an existing block of a pinned material; false when one does not. */
    private boolean references(String slot) {
        boolean resolved = true;
        JsonNode blocks = exercise.path(slot);
        for (int index = 0; index < blocks.size(); index++) {
            JsonNode block = blocks.get(index);
            if (block.path("kind").stringValue("").equals("MATERIAL") && context.block(block.path("ref").stringValue("")).isEmpty()) {
                add(REF_UNKNOWN_HANDLE, slot + "[" + index + "]");
                resolved = false;
            }
        }
        return resolved;
    }

    /** The text the learner reads in a slot: TEXT blocks verbatim, MATERIAL blocks as the pinned text, joined by newlines. */
    private Optional<String> text(String slot) {
        List<String> parts = new ArrayList<>();
        for (JsonNode block : exercise.path(slot)) {
            if (block.path("kind").stringValue("").equals("MATERIAL")) {
                Optional<ExerciseContext.Block> pinned = context.block(block.path("ref").stringValue(""));
                if (pinned.isEmpty()) return Optional.empty();
                parts.add(pinned.get().text());
            } else {
                parts.add(block.path("text").stringValue(""));
            }
        }
        return Optional.of(String.join("\n", parts));
    }

    private boolean hasContent(String slot) {
        for (JsonNode block : exercise.path(slot)) {
            if (block.path("kind").stringValue("").equals("MATERIAL") || !block.path("text").stringValue("").isBlank()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------ SELF_CHECK

    private void selfCheck(boolean resolved) {
        if (!hasContent("reference")) add(SELF_CHECK_REFERENCE_BLANK, "reference");
        if (!resolved) return;
        String prompt = ExerciseTexts.normalize(text("prompt").orElse(""));
        String reference = ExerciseTexts.normalize(text("reference").orElse(""));
        if (!reference.isEmpty() && prompt.equals(reference)) add(SELF_CHECK_REFERENCE_EQUALS_PROMPT, "reference");
    }

    // ---------------------------------------------------------------------- FREE_RESPONSE

    private void freeResponse(boolean resolved) {
        List<String> accepted = strings(exercise.path("accepted"));
        boolean soft = exercise.path("matchingMode").stringValue("").equals("SOFT");
        List<String> normalization = exercise.has("normalization") ? strings(exercise.path("normalization")) : DEFAULT_NORMALIZATION;
        if (soft && accepted.stream().anyMatch(answer -> SoftTextNormalizer.normalize(answer).isEmpty())) {
            add(FREE_RESPONSE_SOFT_EMPTY, "accepted");
        }
        TextRule rule = new TextRule(accepted, normalization, soft);
        Set<String> seen = new HashSet<>();
        for (String answer : accepted) {
            if (!seen.add(rule.canonical(answer))) {
                add(FREE_RESPONSE_ALTERNATIVES_NOT_DISTINCT, "accepted");
                break;
            }
        }
        if (!resolved) return;
        String prompt = ExerciseTexts.normalize(text("prompt").orElse(""));
        for (String answer : accepted) {
            if (ExerciseTexts.containsWord(prompt, ExerciseTexts.normalize(answer))) {
                add(FREE_RESPONSE_ANSWER_IN_PROMPT, "accepted");
                break;
            }
        }
    }

    // --------------------------------------------------------------------------- CLOZE

    private void cloze() {
        List<String> passageBlanks = new ArrayList<>();
        for (JsonNode segment : exercise.path("passage")) {
            if (segment.path("kind").stringValue("").equals("BLANK")) passageBlanks.add(segment.path("blank").stringValue(""));
        }
        Map<String, JsonNode> keyed = new LinkedHashMap<>();
        List<String> keyBlanks = new ArrayList<>();
        for (JsonNode blank : exercise.path("blanks")) {
            keyBlanks.add(blank.path("blank").stringValue(""));
            keyed.putIfAbsent(blank.path("blank").stringValue(""), blank);
        }
        if (hasDuplicates(passageBlanks) || hasDuplicates(keyBlanks)) add(DUPLICATE_LOCAL_ID, "blanks");
        boolean covers = new HashSet<>(passageBlanks).equals(new HashSet<>(keyBlanks));
        if (!covers) {
            add(CLOZE_KEY_BLANK_MISMATCH, "blanks");
            return;
        }
        for (JsonNode segment : exercise.path("passage")) {
            if (!segment.path("kind").stringValue("").equals("BLANK")
                    || !segment.path("size").path("mode").stringValue("").equals("ANSWER_LENGTH")) continue;
            Set<Integer> lengths = new HashSet<>();
            for (String answer : strings(keyed.get(segment.path("blank").stringValue("")).path("accepted"))) {
                String canonical = Normalizer.normalize(answer, Normalizer.Form.NFC);
                lengths.add(canonical.codePointCount(0, canonical.length()));
            }
            if (lengths.size() > 1) {
                add(CLOZE_ANSWER_LENGTH_MISMATCH, "blanks");
                break;
            }
        }
        fragment(keyed);
    }

    /** The passage with the first accepted answer in every blank must be a fragment of one pinned block. */
    private void fragment(Map<String, JsonNode> keyed) {
        ExerciseContext.Material material = context.materials().get(exercise.path("subject").stringValue(""));
        if (material == null) return;
        StringBuilder filled = new StringBuilder();
        for (JsonNode segment : exercise.path("passage")) {
            if (segment.path("kind").stringValue("").equals("TEXT")) {
                filled.append(segment.path("text").stringValue(""));
            } else {
                JsonNode accepted = keyed.get(segment.path("blank").stringValue("")).path("accepted");
                filled.append(accepted.isEmpty() ? "" : accepted.get(0).stringValue(""));
            }
        }
        String wanted = ExerciseTexts.collapse(filled.toString());
        boolean found = !wanted.isEmpty() && material.blocks().values().stream()
                .anyMatch(block -> ExerciseTexts.collapse(block.text()).contains(wanted));
        if (!found) add(CLOZE_FRAGMENT_NOT_IN_MATERIAL, "passage");
    }

    // --------------------------------------------------------------------------- CHOICE

    private void choice() {
        List<String> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        int correct = 0;
        for (JsonNode option : exercise.path("options")) {
            ids.add(option.path("id").stringValue(""));
            texts.add(ExerciseTexts.normalize(option.path("text").stringValue("")));
            if (option.path("correct").booleanValue(false)) correct++;
        }
        if (hasDuplicates(ids)) add(DUPLICATE_LOCAL_ID, "options");
        if (hasDuplicates(texts)) add(CHOICE_OPTIONS_NOT_DISTINCT, "options");
        boolean single = exercise.path("selectionMode").stringValue("").equals("SINGLE");
        if (single ? correct != 1 : correct < 1) add(CHOICE_CORRECT_COUNT, "options");
        if (texts.stream().anyMatch(text -> ALL_OR_NONE.stream().anyMatch(phrase -> ExerciseTexts.containsWord(text, phrase)))) {
            add(CHOICE_ALL_OR_NONE_OF_THE_ABOVE, "options");
        }
    }

    // ---------------------------------------------------------------------------- MATCH

    private void match() {
        List<String> lefts = ids(exercise.path("left"));
        List<String> rights = ids(exercise.path("right"));
        if (hasDuplicates(lefts) || hasDuplicates(rights)) add(DUPLICATE_LOCAL_ID, "left");
        Map<String, String> pairs = new LinkedHashMap<>();
        boolean bijection = lefts.size() == rights.size() && exercise.path("pairs").size() == lefts.size();
        for (JsonNode pair : exercise.path("pairs")) {
            String left = pair.path("left").stringValue("");
            String right = pair.path("right").stringValue("");
            bijection &= lefts.contains(left) && rights.contains(right) && pairs.put(left, right) == null;
        }
        bijection &= new HashSet<>(pairs.values()).size() == pairs.size();
        if (!bijection) add(MATCH_NOT_BIJECTION, "pairs");
        Map<String, String> leftText = labels(exercise.path("left"));
        Map<String, String> rightText = labels(exercise.path("right"));
        if (hasDuplicates(List.copyOf(leftText.values())) || hasDuplicates(List.copyOf(rightText.values()))
                || leftText.size() != lefts.size() || rightText.size() != rights.size()) {
            add(MATCH_LABELS_NOT_DISTINCT, "left");
        }
        if (!bijection) return;
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            String left = leftText.get(pair.getKey());
            String right = rightText.get(pair.getValue());
            if (left != null && right != null && (ExerciseTexts.containsWord(right, left) || ExerciseTexts.containsWord(left, right))) {
                add(MATCH_LABEL_LEAKS_PAIR, "pairs");
                break;
            }
        }
    }

    private static Map<String, String> labels(JsonNode side) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (JsonNode item : side) labels.putIfAbsent(item.path("id").stringValue(""), ExerciseTexts.normalize(item.path("text").stringValue("")));
        return labels;
    }

    // ---------------------------------------------------------------------------- ORDER

    private void order() {
        List<String> ids = ids(exercise.path("items"));
        if (hasDuplicates(ids)) add(DUPLICATE_LOCAL_ID, "items");
        Set<String> distinct = new HashSet<>();
        for (JsonNode item : exercise.path("items")) distinct.add(ExerciseTexts.normalize(item.path("text").stringValue("")));
        if (distinct.size() < 2) add(ORDER_ITEMS_NOT_DISTINGUISHABLE, "items");
    }

    // ----------------------------------------------------------------------- CATEGORIZE

    private void categorize() {
        List<String> categories = ids(exercise.path("categories"));
        if (hasDuplicates(categories)) add(DUPLICATE_LOCAL_ID, "categories");
        if (hasDuplicates(ids(exercise.path("items")))) add(DUPLICATE_LOCAL_ID, "items");
        Set<String> used = new HashSet<>();
        boolean known = true;
        for (JsonNode item : exercise.path("items")) {
            String category = item.path("category").stringValue("");
            if (categories.contains(category)) used.add(category);
            else known = false;
        }
        if (!known) {
            add(CATEGORIZE_UNKNOWN_CATEGORY, "items");
            return;
        }
        if (used.size() < 2) add(CATEGORIZE_TOO_FEW_NON_EMPTY_CATEGORIES, "items");
    }

    // ---------------------------------------------------------------------------- helpers

    private static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : list) ids.add(item.path("id").stringValue(""));
        return ids;
    }

    private static List<String> strings(JsonNode list) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : list) values.add(item.stringValue(""));
        return values;
    }

    private static boolean hasDuplicates(List<String> values) {
        return new HashSet<>(values).size() != values.size();
    }
}
