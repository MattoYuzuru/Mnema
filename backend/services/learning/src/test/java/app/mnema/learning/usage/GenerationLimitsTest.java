package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationLimitsTest {
    @Test
    void limitsMustBeBetweenOneAndAThousand() {
        assertThatThrownBy(() -> new GenerationLimits(0, 20, 20, 10, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationLimits(20, 20, 20, 10, 1_001)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new GenerationLimits(1, 1, 1, 1, 1).maxSources).isOne();
    }

    @Test
    void theProblemNamesOnlyTheLimitsThatApply() {
        var limits = new GenerationLimits(20, 20, 20, 10, 60);
        assertThat(limits.exceeded("SOURCES").extension().members()).containsEntry("limits", Map.of("maxSources", 20));
        assertThat(limits.exceeded("EXERCISES_PER_TARGET").extension().members().get("limits"))
                .isEqualTo(Map.of("maxExerciseTargets", 20, "maxExercisesPerTarget", 10, "maxExercisesPerSession", 60));
    }
}
