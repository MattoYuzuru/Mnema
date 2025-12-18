package app.mnema.core.review.controller.dto;

import app.mnema.core.review.domain.Rating;

import java.time.Instant;
import java.util.UUID;

public record ReviewAnswerResponse(
        UUID answeredCardId,
        Rating rating,
        Instant nextReviewAt,
        ReviewNextCardResponse next
) {
}
