package app.mnema.learning.generation;

import app.mnema.learning.generation.exercise.ExerciseOutputSchema;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code learning.generation.*} and builds the exercise validation pipeline; the rest of the module is component-scanned. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GenerationSettings.class)
class GenerationConfiguration {
    @Bean
    ExerciseOutputSchema exerciseOutputSchema() {
        return ExerciseOutputSchema.load();
    }

    @Bean
    ExerciseValidator exerciseValidator(ExerciseOutputSchema schema) {
        return new ExerciseValidator(schema);
    }
}
