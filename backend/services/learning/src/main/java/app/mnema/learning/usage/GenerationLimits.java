package app.mnema.learning.usage;

import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session limits the estimate enforces with {@code 422 RESOURCE_LIMIT_EXCEEDED} and no silent clamp
 * ({@code learning.generation.max-*}). The exercise limits are owner decisions of 2026-10-02.
 */
@Component
final class GenerationLimits {
    final int maxSources;
    final int maxArtifactsPerSession;
    final int maxExerciseTargets;
    final int maxExercisesPerTarget;
    final int maxExercisesPerSession;

    GenerationLimits(@Value("${learning.generation.max-sources:20}") int maxSources,
                     @Value("${learning.generation.max-artifacts-per-session:20}") int maxArtifactsPerSession,
                     @Value("${learning.generation.max-exercise-targets:20}") int maxExerciseTargets,
                     @Value("${learning.generation.max-exercises-per-target:10}") int maxExercisesPerTarget,
                     @Value("${learning.generation.max-exercises-per-session:60}") int maxExercisesPerSession) {
        for (int value : new int[] {maxSources, maxArtifactsPerSession, maxExerciseTargets, maxExercisesPerTarget,
                maxExercisesPerSession}) {
            if (value < 1 || value > 1_000) throw new IllegalArgumentException("Invalid generation limit");
        }
        this.maxSources = maxSources;
        this.maxArtifactsPerSession = maxArtifactsPerSession;
        this.maxExerciseTargets = maxExerciseTargets;
        this.maxExercisesPerTarget = maxExercisesPerTarget;
        this.maxExercisesPerSession = maxExercisesPerSession;
    }

    /** {@code limit} is a value of the contract's {@code RESOURCE_LIMIT_EXCEEDED.limit}. */
    ResourceLimitExceededException exceeded(String limit) {
        Map<String, Object> limits = new LinkedHashMap<>();
        switch (limit) {
            case "SOURCES" -> limits.put("maxSources", maxSources);
            case "ARTIFACTS_PER_SESSION" -> limits.put("maxArtifactsPerSession", maxArtifactsPerSession);
            default -> {
                limits.put("maxExerciseTargets", maxExerciseTargets);
                limits.put("maxExercisesPerTarget", maxExercisesPerTarget);
                limits.put("maxExercisesPerSession", maxExercisesPerSession);
            }
        }
        return new ResourceLimitExceededException(ProblemExtension.builder().put("limit", limit).put("limits", limits).build());
    }
}
