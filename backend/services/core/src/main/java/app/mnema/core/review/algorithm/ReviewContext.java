package app.mnema.core.review.algorithm;

import app.mnema.core.review.domain.ReviewSource;
import com.fasterxml.jackson.databind.JsonNode;

public record ReviewContext(
        ReviewSource source,
        Integer responseMs,
        JsonNode features
) {
    public static final ReviewContext EMPTY = new ReviewContext(null, null, null);
}
