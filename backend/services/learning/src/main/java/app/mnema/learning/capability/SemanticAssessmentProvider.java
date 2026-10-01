package app.mnema.learning.capability;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Seam for a future rubric-based semantic assessment provider (the {@code ai-semantic} evaluator).
 *
 * <p>No implementation exists and no SDK, key or network call belongs here. A provider only returns a
 * judgement and its evidence; it never writes {@code StudyState}: the baseline reducer remains the only
 * writer. Level mapping: COMPLETE to CORRECT, PARTIAL to PARTIAL, INSUFFICIENT to INCORRECT; an uncertain
 * judgement is UNSURE and a provider failure is UNAVAILABLE, never a learner error.
 */
public interface SemanticAssessmentProvider {
    /** Judge one learner response against the exercise rubric. */
    Judgement assess(JsonNode rubric, String learnerResponse);

    enum Level { COMPLETE, PARTIAL, INSUFFICIENT, UNSURE, UNAVAILABLE }

    record Judgement(Level level, String evidence) { }
}
