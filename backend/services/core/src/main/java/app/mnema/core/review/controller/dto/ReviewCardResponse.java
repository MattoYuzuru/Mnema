package app.mnema.core.review.controller.dto;

import app.mnema.core.review.domain.Rating;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReviewCardResponse(
        UUID userDeckId,
        UUID userCardId,
        JsonNode cardContent,
        Map<Rating, String> intervals,
        Instant nextReviewAt
) {}
