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
}
