package app.mnema.core.review.algorithm;

import app.mnema.core.review.algorithm.impl.FsrsV6Algorithm;
import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FsrsV6AlgorithmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initialStateAndIdExposeFsrsDefaults() {
        FsrsV6Algorithm algorithm = new FsrsV6Algorithm(MAPPER);

        JsonNode state = algorithm.initialState(MAPPER.createObjectNode());

        assertThat(algorithm.id()).isEqualTo("fsrs_v6");
        assertThat(state.path("phase").asText()).isEqualTo("learning");
        assertThat(state.path("step").asInt()).isZero();
    }

    @Test
    void canonicalConversionPreservesReviewShape() {
        FsrsV6Algorithm algorithm = new FsrsV6Algorithm(MAPPER);

        JsonNode state = algorithm.fromCanonical(new CanonicalProgress(0.5, 12.0), MAPPER.createObjectNode());
        CanonicalProgress progress = algorithm.toCanonical(state);

        assertThat(state.path("phase").asText()).isEqualTo("review");
        assertThat(progress.difficulty01()).isBetween(0.49, 0.51);
        assertThat(progress.stabilityDays()).isEqualTo(12.0);
    }

    @Test
    void applyGraduatesLearningCardOnEasy() {
        FsrsV6Algorithm algorithm = new FsrsV6Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ObjectNode config = baseConfig();
        config.put("easyIntervalDays", 5);

        SrsAlgorithm.ReviewComputation result = algorithm.apply(
                new SrsAlgorithm.ReviewInput(algorithm.initialState(config), now.minus(Duration.ofMinutes(10)), 0),
                Rating.EASY,
                now,
                config
        );

        assertThat(result.newState().path("phase").asText()).isEqualTo("review");
        assertThat(Duration.between(now, result.nextReviewAt())).isEqualTo(Duration.ofDays(5));
    }

    @Test
    void applyUsesLearningFlowWhenLastReviewMissing() {
        FsrsV6Algorithm algorithm = new FsrsV6Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ObjectNode config = baseConfig();

        SrsAlgorithm.ReviewComputation result = algorithm.apply(
                new SrsAlgorithm.ReviewInput(reviewState(2.0, 5.0), null, 0),
                Rating.GOOD,
                now,
                config
        );

        assertThat(result.newState().path("phase").asText()).isEqualTo("learning");
        assertThat(result.newState().path("step").asInt()).isEqualTo(1);
    }

    @Test
    void applyMovesReviewCardToRelearningOnAgain() {
        FsrsV6Algorithm algorithm = new FsrsV6Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ObjectNode config = baseConfig();
        config.putArray("relearningStepsMinutes").add(15);

        SrsAlgorithm.ReviewComputation result = algorithm.apply(
                new SrsAlgorithm.ReviewInput(reviewState(6.0, 4.0), now.minus(Duration.ofDays(4)), 10),
                Rating.AGAIN,
                now,
                config
        );

        assertThat(result.newState().path("phase").asText()).isEqualTo("relearning");
        assertThat(Duration.between(now, result.nextReviewAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void applyGraduatesRelearningCardWhenStepsComplete() {
        FsrsV6Algorithm algorithm = new FsrsV6Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        ObjectNode config = baseConfig();
        config.put("graduatingIntervalDays", 3);
        config.putArray("relearningStepsMinutes").add(10);

        ObjectNode state = MAPPER.createObjectNode();
        state.put("phase", "relearning");
        state.put("step", 0);
        state.put("s", 5.0);
        state.put("d", 4.0);

        SrsAlgorithm.ReviewComputation result = algorithm.apply(
                new SrsAlgorithm.ReviewInput(state, now.minus(Duration.ofDays(1)), 2),
                Rating.GOOD,
                now,
                config
        );

        assertThat(result.newState().path("phase").asText()).isEqualTo("review");
        assertThat(Duration.between(now, result.nextReviewAt())).isEqualTo(Duration.ofDays(3));
    }

    @Test
    void applyUsesSameDayStabilityForShortRecallIntervals() {
        FsrsV6Algorithm algorithm = new FsrsV6Algorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");

        SrsAlgorithm.ReviewComputation result = algorithm.apply(
                new SrsAlgorithm.ReviewInput(reviewState(3.0, 6.0), now.minus(Duration.ofHours(8)), 5),
                Rating.GOOD,
                now,
                baseConfig()
        );

        assertThat(result.newState().path("phase").asText()).isEqualTo("review");
        assertThat(result.nextReviewAt()).isAfter(now);
    }

    private static ObjectNode baseConfig() {
        ObjectNode config = MAPPER.createObjectNode();
        ArrayNode weights = config.putArray("weights");
        for (double weight : new double[]{
                0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
                0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
        }) {
            weights.add(weight);
        }
        config.putArray("learningStepsMinutes").add(1).add(10);
        config.putArray("relearningStepsMinutes").add(10).add(30);
        config.put("minimumIntervalMinutes", 1);
        config.put("requestRetention", 0.9);
        config.put("maximumIntervalDays", 3650);
        config.put("graduatingIntervalDays", 1);
        config.put("easyIntervalDays", 4);
        return config;
    }

    private static ObjectNode reviewState(double stability, double difficulty) {
        ObjectNode state = MAPPER.createObjectNode();
        state.put("phase", "review");
        state.put("step", 0);
        state.put("s", stability);
        state.put("d", difficulty);
        return state;
    }
}
