package app.mnema.core.review.algorithm;

import app.mnema.core.review.algorithm.impl.HlrAlgorithm;
import app.mnema.core.review.domain.Rating;
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
}
