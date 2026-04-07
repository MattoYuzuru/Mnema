package app.mnema.core.review.algorithm;

import app.mnema.core.review.algorithm.impl.Sm2Algorithm;
import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class Sm2AlgorithmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void apply_preservesReviewStateFields() {
        Sm2Algorithm algorithm = new Sm2Algorithm(MAPPER);

        ObjectNode state = MAPPER.createObjectNode();
        state.put("phase", "review");
        state.put("step", 0);
        state.put("ef", 2.5);
        state.put("intervalDays", 10.0);
        state.put("repetitions", 3);
        state.put("lapses", 1);

        Instant now = Instant.parse("2024-01-02T00:00:00Z");
        SrsAlgorithm.ReviewInput input = new SrsAlgorithm.ReviewInput(
                state,
                now.minus(Duration.ofDays(1)),
                5
        );

        SrsAlgorithm.ReviewComputation out = algorithm.apply(input, Rating.GOOD, now, MAPPER.createObjectNode());
        JsonNode newState = out.newState();

        assertThat(newState.path("lapses").asInt()).isEqualTo(1);
        assertThat(newState.path("ef").isMissingNode()).isFalse();
        assertThat(out.nextReviewAt()).isNotNull();
    }

    @Test
    void initialStateAndCanonicalConversionExposeSm2Defaults() {
        Sm2Algorithm algorithm = new Sm2Algorithm(MAPPER);

        JsonNode state = algorithm.initialState(MAPPER.createObjectNode());
        CanonicalProgress progress = algorithm.toCanonical(state);
        JsonNode restored = algorithm.fromCanonical(new CanonicalProgress(0.4, 12.0), MAPPER.createObjectNode());

        assertThat(algorithm.id()).isEqualTo("sm2");
        assertThat(state.path("phase").asText()).isEqualTo("learning");
        assertThat(state.path("ef").asDouble()).isEqualTo(2.5);
        assertThat(progress.stabilityDays()).isEqualTo(0.1);
        assertThat(restored.path("phase").asText()).isEqualTo("review");
        assertThat(restored.path("repetitions").asInt()).isEqualTo(1);
    }

    @Test
    void applyLearningGraduatesOnEasyOrEmptySteps() {
        Sm2Algorithm algorithm = new Sm2Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");

        ObjectNode config = MAPPER.createObjectNode();
        config.putArray("learningStepsMinutes").add(1).add(10);
        config.put("easyIntervalDays", 5);

        SrsAlgorithm.ReviewComputation easy = algorithm.apply(
                new SrsAlgorithm.ReviewInput(algorithm.initialState(config), now.minus(Duration.ofMinutes(5)), 0),
                Rating.EASY,
                now,
                config
        );

        ObjectNode noSteps = MAPPER.createObjectNode();
        noSteps.putArray("learningStepsMinutes");
        noSteps.put("graduatingIntervalDays", 2);
        SrsAlgorithm.ReviewComputation graduate = algorithm.apply(
                new SrsAlgorithm.ReviewInput(algorithm.initialState(noSteps), now.minus(Duration.ofMinutes(5)), 0),
                Rating.GOOD,
                now,
                noSteps
        );

        assertThat(easy.newState().path("phase").asText()).isEqualTo("review");
        assertThat(Duration.between(now, easy.nextReviewAt())).isEqualTo(Duration.ofDays(5));
        assertThat(graduate.newState().path("phase").asText()).isEqualTo("review");
        assertThat(Duration.between(now, graduate.nextReviewAt())).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void applyLearningAndRelearningAdvanceThroughSteps() {
        Sm2Algorithm algorithm = new Sm2Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ObjectNode config = MAPPER.createObjectNode();
        config.putArray("learningStepsMinutes").add(1).add(10);
        config.putArray("relearningStepsMinutes").add(5);
        config.put("minimumIntervalMinutes", 3);

        SrsAlgorithm.ReviewComputation learning = algorithm.apply(
                new SrsAlgorithm.ReviewInput(algorithm.initialState(config), now.minus(Duration.ofMinutes(1)), 0),
                Rating.GOOD,
                now,
                config
        );

        ObjectNode relearningState = MAPPER.createObjectNode();
        relearningState.put("phase", "relearning");
        relearningState.put("step", 0);
        relearningState.put("ef", 2.5);
        relearningState.put("intervalDays", 8.0);
        relearningState.put("repetitions", 3);
        relearningState.put("lapses", 1);

        SrsAlgorithm.ReviewComputation relearning = algorithm.apply(
                new SrsAlgorithm.ReviewInput(relearningState, now.minus(Duration.ofDays(1)), 3),
                Rating.GOOD,
                now,
                config
        );

        assertThat(learning.newState().path("phase").asText()).isEqualTo("learning");
        assertThat(learning.newState().path("step").asInt()).isEqualTo(1);
        assertThat(Duration.between(now, learning.nextReviewAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(relearning.newState().path("phase").asText()).isEqualTo("review");
        assertThat(relearning.newState().path("intervalDays").asDouble()).isEqualTo(4.0);
    }

    @Test
    void applyReviewHandlesAgainHardAndEasyBranches() {
        Sm2Algorithm algorithm = new Sm2Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ObjectNode config = MAPPER.createObjectNode();
        config.putArray("relearningStepsMinutes").add(15);
        config.put("hardFactor", 2.0);
        config.put("easyBonus", 1.5);

        ObjectNode state = MAPPER.createObjectNode();
        state.put("phase", "review");
        state.put("step", 0);
        state.put("ef", 2.5);
        state.put("intervalDays", 10.0);
        state.put("repetitions", 3);
        state.put("lapses", 0);

        SrsAlgorithm.ReviewComputation again = algorithm.apply(new SrsAlgorithm.ReviewInput(state, now.minus(Duration.ofDays(3)), 4), Rating.AGAIN, now, config);
        SrsAlgorithm.ReviewComputation hard = algorithm.apply(new SrsAlgorithm.ReviewInput(state, now.minus(Duration.ofDays(3)), 4), Rating.HARD, now, config);
        SrsAlgorithm.ReviewComputation easy = algorithm.apply(new SrsAlgorithm.ReviewInput(state, now.minus(Duration.ofDays(3)), 4), Rating.EASY, now, config);

        assertThat(again.newState().path("phase").asText()).isEqualTo("relearning");
        assertThat(again.newState().path("lapses").asInt()).isEqualTo(1);
        assertThat(Duration.between(now, again.nextReviewAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(hard.newState().path("intervalDays").asDouble()).isLessThan(10.0 * 2.5);
        assertThat(easy.newState().path("intervalDays").asDouble()).isGreaterThan(hard.newState().path("intervalDays").asDouble());
    }
}
