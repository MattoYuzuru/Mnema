package app.mnema.core.review.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record SeedCardProgressRequest(
        UUID userCardId,
        double difficulty01,
        double stabilityDays,
        Integer reviewCount,
        Instant lastReviewAt,
        Instant nextReviewAt,
        Boolean suspended
) {
}
