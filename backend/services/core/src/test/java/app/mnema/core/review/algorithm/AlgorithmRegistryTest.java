package app.mnema.core.review.algorithm;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlgorithmRegistryTest {

    @Test
    void requireReturnsRegisteredAlgorithmAndRejectsUnknownIds() {
        SrsAlgorithm algorithm = new NoopAlgorithm("fsrs_v6");
        AlgorithmRegistry registry = new AlgorithmRegistry(List.of(algorithm));

        assertThat(registry.require("fsrs_v6")).isSameAs(algorithm);
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported algorithm");
    }

    private record NoopAlgorithm(String id) implements SrsAlgorithm {
        @Override
        public com.fasterxml.jackson.databind.JsonNode initialState(com.fasterxml.jackson.databind.JsonNode effectiveConfig) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }

        @Override
        public ReviewComputation apply(ReviewInput input, app.mnema.core.review.domain.Rating rating, Instant now, com.fasterxml.jackson.databind.JsonNode effectiveConfig) {
            return new ReviewComputation(initialState(effectiveConfig), now, now, 1);
        }

        @Override
        public CanonicalProgress toCanonical(com.fasterxml.jackson.databind.JsonNode state) {
            return new CanonicalProgress(0.5, 1.0);
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode fromCanonical(CanonicalProgress progress, com.fasterxml.jackson.databind.JsonNode effectiveConfig) {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
    }
}
