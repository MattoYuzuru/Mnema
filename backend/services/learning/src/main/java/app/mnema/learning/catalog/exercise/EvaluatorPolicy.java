package app.mnema.learning.catalog.exercise;

import tools.jackson.databind.JsonNode;

import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;

/**
 * Evaluator identity of one exercise revision. Each mechanic has one deterministic evaluator;
 * FREE_RESPONSE may instead name the {@code ai-semantic} evaluator with a structurally valid {@link Rubric}.
 *
 * <p>Mapping of that evaluator ({@code contracts/study}, AI assessment): the server aggregates the model's verdicts per rubric
 * point into COMPLETE, PARTIAL or INSUFFICIENT under a strictness level and maps them to CORRECT, PARTIAL and INCORRECT
 * evidence. Provider uncertainty is never a result: the learner rates themselves. A provider failure is never a learner error.
 * {@code rubric} is null for every other evaluator.
 */
public record EvaluatorPolicy(String id, String version, Rubric rubric) {
    public static final String SEMANTIC = "ai-semantic";

    public boolean semantic() { return SEMANTIC.equals(id); }

    static EvaluatorPolicy parse(ExerciseType type, JsonNode value) {
        if (!value.isObject() || !"1".equals(value.path("version").stringValue(null))
                || !value.path("id").isString()) throw invalid();
        String id = value.path("id").stringValue(null);
        if (type == ExerciseType.FREE_RESPONSE && SEMANTIC.equals(id)) {
            fields(value, "id", "version", "rubric");
            return new EvaluatorPolicy(id, "1", Rubric.read(value.path("rubric")));
        }
        fields(value, "id", "version");
        if (!type.evaluatorId().equals(id)) throw invalid();
        return new EvaluatorPolicy(id, "1", null);
    }
}
