package app.mnema.learning.generation.exercise;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the server pinned for one exercise answer ({@code contracts/generation/exercises/README.md}, compile rules): the
 * pinned materials with their top-level block handles, the objectives offered to the model and the mechanics the task
 * allows. The model never sees an identifier; handles ({@code m1}, {@code m1:b3}, {@code t1}) resolve through this record.
 *
 * @param commandId placeholder of the compiled command (supplied at approval)
 * @param expectedDeckRevisionId placeholder of the compiled command (supplied at approval)
 * @param materials {@code m1 -> material}; a step pins exactly one
 * @param objectives {@code t1 -> objective} offered to the model
 * @param allowedMechanics the mechanics the task allows (an exercise of another mechanic is {@code MECHANIC_NOT_ALLOWED})
 */
public record ExerciseContext(UUID commandId, UUID expectedDeckRevisionId, Map<String, Material> materials,
                              Map<String, Objective> objectives, Set<String> allowedMechanics) {
    /** The seven mechanics of the Study contract. */
    public static final Set<String> ALL_MECHANICS = Set.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER",
            "CATEGORIZE");

    public ExerciseContext {
        materials = Map.copyOf(materials);
        objectives = Map.copyOf(objectives);
        allowedMechanics = Set.copyOf(allowedMechanics);
    }

    /** One top-level block of a pinned material: the node it quotes and its plain text. */
    public record Block(UUID nodeId, String text) { }

    /** A pinned material revision and its blocks by handle ({@code b1}...); a block without quotable text is absent. */
    public record Material(UUID memberKey, UUID itemRevisionId, Map<String, Block> blocks) {
        public Material {
            blocks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
        }
    }

    /** An objective the task offered; {@code title} is null when only the revision is known (fixtures). */
    public record Objective(UUID objectiveId, UUID objectiveRevisionId, String title) { }

    public Optional<Block> block(String reference) {
        int colon = reference.indexOf(':');
        if (colon < 0) return Optional.empty();
        Material material = materials.get(reference.substring(0, colon));
        return material == null ? Optional.empty() : Optional.ofNullable(material.blocks().get(reference.substring(colon + 1)));
    }

    /** The offered objective whose title equals {@code title} after normalization, if any (decision D5). */
    public Optional<Objective> objectiveTitled(String title) {
        String wanted = ExerciseTexts.normalize(title);
        return objectives.values().stream().filter(objective -> objective.title() != null
                && ExerciseTexts.normalize(objective.title()).equals(wanted)).findFirst();
    }

    /** The ordered set of mechanics, for prompts: the registry order of the contract, not the hash order. */
    public List<String> mechanicsInOrder() {
        Set<String> ordered = new LinkedHashSet<>(List.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER",
                "CATEGORIZE"));
        ordered.retainAll(allowedMechanics);
        return List.copyOf(ordered);
    }
}
