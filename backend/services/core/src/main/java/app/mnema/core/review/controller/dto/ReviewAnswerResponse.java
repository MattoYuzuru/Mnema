package app.mnema.core.review.controller.dto;

import app.mnema.core.review.domain.Rating;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReviewAnswerResponse(
        UUID answeredCardId,
        Rating rating,
        Instant nextReviewAt,
        ReviewNextCardResponse next,
        Completion completion
) {
    public record Completion(
            boolean firstCompletionToday,
            int completionIndexToday,
            LocalDate reviewDay,
            StreakProgress streak,
            SessionSnapshot session
    ) {
    }

    public record StreakProgress(
            long previousStreakDays,
            long currentStreakDays,
            long longestStreakDays
    ) {
    }

    public record SessionSnapshot(
            Instant startedAt,
            Instant endedAt,
            long durationMinutes,
            long reviewCount,
            long totalResponseMs
    ) {
    }
}
