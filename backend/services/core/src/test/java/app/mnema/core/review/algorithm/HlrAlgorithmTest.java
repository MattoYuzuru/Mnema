package app.mnema.core.review.algorithm;

import app.mnema.core.review.algorithm.impl.HlrAlgorithm;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.domain.ReviewSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HlrAlgorithmTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void review_returnsUpdatedWeightsAndNextReview() {
        HlrAlgorithm algorithm = new HlrAlgorithm(MAPPER);
        JsonNode config = MAPPER.createObjectNode();

        Instant now = Instant.parse("2024-01-02T00:00:00Z");
        JsonNode state = algorithm.initialState(config);
        SrsAlgorithm.ReviewInput input = new SrsAlgorithm.ReviewInput(
                state,
                now.minus(Duration.ofDays(2)),
                1
        );

        ReviewContext context = new ReviewContext(null, 1200, null);
        SrsAlgorithm.ReviewOutcome outcome = algorithm.review(input, Rating.GOOD, now, config, context, null);

        assertThat(outcome.updatedDeckConfig()).isNotNull();
        JsonNode updated = outcome.updatedDeckConfig();
        assertThat(updated.path("weights").isArray()).isTrue();
        int featureSize = updated.path("featureSize").asInt();
        assertThat(featureSize).isEqualTo(updated.path("weights").size());
        assertThat(outcome.computation().nextReviewAt()).isNotNull();
    }

    @Test
    void initialStateAndCanonicalConversionExposeHlrDefaults() {
        HlrAlgorithm algorithm = new HlrAlgorithm(MAPPER);

        JsonNode state = algorithm.initialState(MAPPER.createObjectNode());
        CanonicalProgress progress = algorithm.toCanonical(state);
        JsonNode restored = algorithm.fromCanonical(new CanonicalProgress(0.2, 5.0), MAPPER.createObjectNode());

        assertThat(algorithm.id()).isEqualTo("hlr");
        assertThat(state.path("phase").asText()).isEqualTo("learning");
        assertThat(state.path("h").asDouble()).isEqualTo(1.0);
        assertThat(progress.stabilityDays()).isEqualTo(1.0);
        assertThat(restored.path("phase").asText()).isEqualTo("review");
        assertThat(restored.path("h").asDouble()).isEqualTo(5.0);
    }

    @Test
    void applyUsesDirectFeatureVectorAndGraduatesEasyLearningCards() {
        HlrAlgorithm algorithm = new HlrAlgorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        var config = MAPPER.createObjectNode();
        config.put("easyIntervalDays", 6);

        var features = MAPPER.createObjectNode();
        features.putArray("x").add(1.0).add(0.2).add(0.0).add(0.1);

        var outcome = algorithm.review(
                new SrsAlgorithm.ReviewInput(algorithm.initialState(config), now.minus(Duration.ofMinutes(5)), 1),
                Rating.EASY,
                now,
                config,
                new ReviewContext(ReviewSource.web, 500, features),
                MAPPER.createObjectNode()
        );

        assertThat(outcome.computation().newState().path("phase").asText()).isEqualTo("review");
        assertThat(Duration.between(now, outcome.computation().nextReviewAt())).isEqualTo(Duration.ofDays(6));
        assertThat(outcome.updatedDeckConfig().path("weights").isArray()).isTrue();
    }

    @Test
    void applyHandlesRelearningAndFallbackFeatureVector() {
        HlrAlgorithm algorithm = new HlrAlgorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        var config = MAPPER.createObjectNode();
        config.putArray("relearningStepsMinutes").add(12);
        config.put("minimumIntervalMinutes", 5);

        var state = MAPPER.createObjectNode();
        state.put("phase", "relearning");
        state.put("step", 0);
        state.put("h", 2.0);

        var outcome = algorithm.apply(
                new SrsAlgorithm.ReviewInput(state, now.minus(Duration.ofDays(1)), 3),
                Rating.AGAIN,
                now,
                config,
                new ReviewContext(ReviewSource.web, 400, MAPPER.createObjectNode())
        );

        assertThat(outcome.newState().path("phase").asText()).isEqualTo("relearning");
        assertThat(Duration.between(now, outcome.nextReviewAt())).isEqualTo(Duration.ofMinutes(12));
    }

    @Test
    void applyAdjustsReviewIntervalForHardAndEasyRatings() {
        HlrAlgorithm algorithm = new HlrAlgorithm(MAPPER);
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        var config = MAPPER.createObjectNode();
        config.put("requestRetention", 0.9);

        var state = MAPPER.createObjectNode();
        state.put("phase", "review");
        state.put("step", 0);
        state.put("h", 4.0);

        var hard = algorithm.apply(
                new SrsAlgorithm.ReviewInput(state, now.minus(Duration.ofDays(2)), 5),
                Rating.HARD,
                now,
                config,
                new ReviewContext(ReviewSource.web, 700, null)
        );
        var easy = algorithm.apply(
                new SrsAlgorithm.ReviewInput(state, now.minus(Duration.ofDays(2)), 5),
                Rating.EASY,
                now,
                config,
                new ReviewContext(ReviewSource.web, 700, null)
        );

        assertThat(hard.newState().path("phase").asText()).isEqualTo("review");
        assertThat(easy.nextReviewAt()).isAfter(hard.nextReviewAt());
    }
}
