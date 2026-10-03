package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Source;
import app.mnema.learning.generation.exercise.ExerciseContext;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The parts of a validated {@code EXERCISES} spec that the session reads (the usage module's interpreter has already refused
 * every malformed or over-limit spec, so this reader only picks values and applies the contract's defaults).
 *
 * @param targets the materials, in request order: pinned as {@code SOURCE} items of the session
 * @param mechanics the requested set, or null for {@code AUTO}
 * @param priority {@code UNCOVERED_FIRST} or {@code BALANCED}
 */
record ExercisesSpec(List<Source> targets, String outputLanguage, List<String> mechanics, String priority) {
    static final String UNCOVERED_FIRST = "UNCOVERED_FIRST";

    static ExercisesSpec read(JsonNode spec) {
        List<Source> targets = new ArrayList<>();
        int ordinal = 0;
        for (JsonNode target : spec.path("targets")) {
            targets.add(new Source(ordinal++, "SOURCE", "ITEM", null, null, UUID.fromString(target.path("memberKey").stringValue("")),
                    UUID.fromString(target.path("itemRevisionId").stringValue(""))));
        }
        JsonNode settings = spec.path("settings");
        List<String> mechanics = null;
        if (settings.path("mechanics").isArray()) {
            Set<String> chosen = new LinkedHashSet<>();
            settings.path("mechanics").forEach(mechanic -> chosen.add(mechanic.stringValue("")));
            mechanics = List.copyOf(chosen);
        }
        return new ExercisesSpec(List.copyOf(targets), spec.path("outputLanguage").stringValue(MaterialsSpec.DEFAULT_LANGUAGE), mechanics,
                settings.path("priority").stringValue(UNCOVERED_FIRST));
    }

    /**
     * The mechanics the model may use, in the registry order of the contract. {@code AUTO} allows all seven: nothing generated here
     * needs a capability that can be off (a {@code FREE_RESPONSE} is deterministic text, never the {@code ai-semantic} evaluator,
     * which AI-20 generates).
     */
    List<String> allowedMechanics() {
        Set<String> allowed = new LinkedHashSet<>(List.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE"));
        if (mechanics != null) allowed.retainAll(mechanics);
        return List.copyOf(allowed);
    }

    /** The set form for the validation context. */
    Set<String> allowedSet() {
        return mechanics == null ? ExerciseContext.ALL_MECHANICS : Set.copyOf(mechanics);
    }

    /**
     * How many exercises each target gets: {@code total} spread over the targets in their processing order, so that the first
     * {@code total mod n} targets get one more. For {@code EXACT} and {@code AUTO} every target gets the same number; for a
     * budget that does not buy a whole round, the targets that are processed first (the uncovered ones) are served first.
     */
    static int[] spread(int total, int targets) {
        int[] counts = new int[targets];
        for (int index = 0; index < targets; index++) counts[index] = total / targets + (index < total % targets ? 1 : 0);
        return counts;
    }
}
