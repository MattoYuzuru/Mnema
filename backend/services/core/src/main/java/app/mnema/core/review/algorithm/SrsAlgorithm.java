package app.mnema.core.review.algorithm;

import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public interface SrsAlgorithm {

    String id();

    JsonNode initialState(JsonNode effectiveConfig);

    ReviewComputation apply(ReviewInput input, Rating rating, Instant now, JsonNode effectiveConfig);

    default ReviewComputation apply(ReviewInput input,
                                    Rating rating,
                                    Instant now,
                                    JsonNode effectiveConfig,
                                    ReviewContext context) {
        return apply(input, rating, now, effectiveConfig);
    }

    default ReviewOutcome review(ReviewInput input,
                                 Rating rating,
                                 Instant now,
                                 JsonNode effectiveConfig,
                                 ReviewContext context,
                                 JsonNode deckConfig) {
        return new ReviewOutcome(apply(input, rating, now, effectiveConfig, context), null);
    }

    CanonicalProgress toCanonical(JsonNode state);

    JsonNode fromCanonical(CanonicalProgress progress, JsonNode effectiveConfig);

    default Map<Rating, Instant> previewNextReviewAt(ReviewInput input, Instant now, JsonNode effectiveConfig) {
        Map<Rating, Instant> out = new EnumMap<>(Rating.class);
        for (Rating r : Rating.values()) {
            out.put(r, apply(input, r, now, effectiveConfig).nextReviewAt());
        }
        return out;
    }

    record ReviewInput(
            JsonNode state,
            Instant lastReviewAt,
            int reviewCount
    ) {
    }

    record ReviewComputation(
            JsonNode newState,
            Instant nextReviewAt,
            Instant lastReviewAt,
            int reviewCountDelta
    ) {
    }

    record ReviewOutcome(
            ReviewComputation computation,
            JsonNode updatedDeckConfig
    ) {
    }
}
