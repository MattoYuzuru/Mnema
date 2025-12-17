package app.mnema.core.review.controller.dto;

import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReviewNextCardResponse(
        UUID userDeckId,
        String algorithmId,
        UUID userCardId,
        UUID publicCardId,
        boolean isCustom,
        JsonNode effectiveContent,
        Map<Rating, String> intervals,
        Instant dueAt,
        boolean due
) {
}
