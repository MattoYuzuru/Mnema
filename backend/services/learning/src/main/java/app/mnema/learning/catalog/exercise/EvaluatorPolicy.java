package app.mnema.learning.catalog.exercise;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static app.mnema.learning.catalog.exercise.StrictJson.array;
import static app.mnema.learning.catalog.exercise.StrictJson.bool;
import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;
import static app.mnema.learning.catalog.exercise.StrictJson.nonBlank;

/**
 * Evaluator identity of one exercise revision. Each mechanic has one deterministic evaluator;
 * FREE_RESPONSE may instead name the future {@code ai-semantic} evaluator with a structurally valid rubric.
 *
 * <p>Documented mapping for that evaluator: rubric level COMPLETE maps to CORRECT, PARTIAL to PARTIAL and
 * INSUFFICIENT to INCORRECT; provider uncertainty is UNSURE and provider failure is UNAVAILABLE, never a
 * learner error.
 */
public record EvaluatorPolicy(String id, String version) {
    public static final String SEMANTIC = "ai-semantic";
    private static final List<String> LEVELS = List.of("COMPLETE", "PARTIAL", "INSUFFICIENT");

    public boolean semantic() { return SEMANTIC.equals(id); }

    static EvaluatorPolicy parse(ExerciseType type, JsonNode value) {
        if (!value.isObject() || !"1".equals(value.path("version").textValue())
                || !value.path("id").isTextual()) throw invalid();
        String id = value.path("id").textValue();
        if (type == ExerciseType.FREE_RESPONSE && SEMANTIC.equals(id)) {
            fields(value, "id", "version", "rubric");
            rubric(value.path("rubric"));
        } else {
            fields(value, "id", "version");
            if (!type.evaluatorId().equals(id)) throw invalid();
        }
        return new EvaluatorPolicy(id, "1");
    }

    private static void rubric(JsonNode rubric) {
        fields(rubric, "referenceAnswer", "criteria", "levels");
        nonBlank(rubric.path("referenceAnswer"), 4_000);
        Set<java.util.UUID> ids = new HashSet<>();
        for (JsonNode criterion : array(rubric.path("criteria"), 1, 10)) {
            fields(criterion, "criterionId", "description", "critical");
            if (!ids.add(StrictJson.id(criterion, "criterionId"))) throw invalid();
            nonBlank(criterion.path("description"), 500);
            bool(criterion.path("critical"));
        }
        List<JsonNode> levels = array(rubric.path("levels"), LEVELS.size(), LEVELS.size());
        for (int index = 0; index < levels.size(); index++) {
            fields(levels.get(index), "level", "description");
            if (!LEVELS.get(index).equals(levels.get(index).path("level").textValue())) throw invalid();
            nonBlank(levels.get(index).path("description"), 500);
        }
    }
}
