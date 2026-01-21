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
        Map<Rating, IntervalPreview> intervals,
        Instant dueAt,
        boolean due,
        QueueSummary queue
) {
    public record IntervalPreview(Instant at, String display) {}

    public record QueueSummary(long dueCount,
                               long newCount,
                               long totalRemaining,
                               long dueTodayCount,
                               long newTotalCount,
                               long learningAheadCount) {}
}
