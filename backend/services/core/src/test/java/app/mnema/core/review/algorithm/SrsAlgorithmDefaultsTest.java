package app.mnema.core.review.algorithm;

import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SrsAlgorithmDefaultsTest {

    @Test
    void defaultReviewAndPreviewDelegateThroughApply() {
        Instant now = Instant.parse("2026-04-07T10:15:30Z");
        SrsAlgorithm algorithm = new SrsAlgorithm() {
            @Override
            public String id() {
                return "noop";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode initialState(com.fasterxml.jackson.databind.JsonNode effectiveConfig) {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public ReviewComputation apply(ReviewInput input, Rating rating, Instant currentNow, com.fasterxml.jackson.databind.JsonNode effectiveConfig) {
                return new ReviewComputation(JsonNodeFactory.instance.objectNode(), currentNow.plusSeconds(rating.code() + 1L), currentNow, 1);
            }

            @Override
            public CanonicalProgress toCanonical(com.fasterxml.jackson.databind.JsonNode state) {
                return new CanonicalProgress(0.5, 1.0);
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode fromCanonical(CanonicalProgress progress, com.fasterxml.jackson.databind.JsonNode effectiveConfig) {
                return JsonNodeFactory.instance.objectNode();
            }
        };

        SrsAlgorithm.ReviewInput input = new SrsAlgorithm.ReviewInput(JsonNodeFactory.instance.objectNode(), now.minusSeconds(60), 1);
        SrsAlgorithm.ReviewOutcome outcome = algorithm.review(input, Rating.GOOD, now, JsonNodeFactory.instance.objectNode(), ReviewContext.EMPTY, null);
        Map<Rating, Instant> preview = algorithm.previewNextReviewAt(input, now, JsonNodeFactory.instance.objectNode());

        assertThat(outcome.computation().nextReviewAt()).isEqualTo(now.plusSeconds(3));
        assertThat(outcome.updatedDeckConfig()).isNull();
        assertThat(preview).containsEntry(Rating.AGAIN, now.plusSeconds(1))
                .containsEntry(Rating.HARD, now.plusSeconds(2))
                .containsEntry(Rating.GOOD, now.plusSeconds(3))
                .containsEntry(Rating.EASY, now.plusSeconds(4));
    }
}
