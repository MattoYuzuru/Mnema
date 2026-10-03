package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.text.SoftTextNormalizer;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static app.mnema.learning.catalog.exercise.StrictJson.array;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;
import static app.mnema.learning.catalog.exercise.StrictJson.nonBlank;
import static app.mnema.learning.catalog.exercise.StrictJson.oneOf;

/**
 * Deterministic text matching rule: any one accepted string suffices after the listed normalizations
 * (and, in SOFT mode, soft normalization). SOFT is not a semantic check.
 */
public record TextRule(List<String> accepted, List<String> normalization, boolean soft) {
    public static final Set<String> NORMALIZATIONS = Set.of("UNICODE_NFC", "TRIM", "CASE_FOLD");

    public TextRule {
        accepted = List.copyOf(accepted);
        normalization = List.copyOf(normalization);
    }

    /** Reads {@code accepted}, {@code normalization} and {@code matchingMode} of an answer-key object. */
    static TextRule read(JsonNode node, int maxAccepted, int maxLength) {
        List<String> accepted = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        boolean soft = "SOFT".equals(oneOf(node.path("matchingMode"), Set.of("STRICT", "SOFT")));
        for (JsonNode entry : array(node.path("accepted"), 1, maxAccepted)) {
            String value = nonBlank(entry, maxLength);
            if (!distinct.add(value) || (soft && SoftTextNormalizer.normalize(value).isEmpty())) throw invalid();
            accepted.add(value);
        }
        List<String> rules = new ArrayList<>();
        for (JsonNode rule : array(node.path("normalization"), 1, NORMALIZATIONS.size())) {
            String name = oneOf(rule, NORMALIZATIONS);
            if (rules.contains(name)) throw invalid();
            rules.add(name);
        }
        return new TextRule(accepted, rules, soft);
    }

    /** True when the supplied text equals any accepted string under this rule; empty never matches. */
    public boolean matches(String supplied) {
        String normalized = canonical(supplied);
        if (normalized.isEmpty()) return false;
        for (String candidate : accepted) if (canonical(candidate).equals(normalized)) return true;
        return false;
    }

    /** Rule names for explainable feedback. */
    public List<String> appliedRules() {
        List<String> rules = new ArrayList<>(normalization);
        if (soft) rules.add("SOFT_MATCH");
        return rules;
    }

    /** The comparison form of a string under this rule; two strings match exactly when their canonical forms are equal. */
    public String canonical(String value) {
        String result = value;
        for (String rule : normalization) {
            result = switch (rule) {
                case "UNICODE_NFC" -> Normalizer.normalize(result, Normalizer.Form.NFC);
                case "TRIM" -> result.strip();
                case "CASE_FOLD" -> result.toLowerCase(Locale.ROOT);
                default -> throw new IllegalStateException("Unknown persisted normalization rule");
            };
        }
        return soft ? SoftTextNormalizer.normalize(result) : result;
    }
}
