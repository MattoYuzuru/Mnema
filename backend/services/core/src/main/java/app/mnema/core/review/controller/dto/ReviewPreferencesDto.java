package app.mnema.core.review.controller.dto;

public record ReviewPreferencesDto(
        Integer dailyNewLimit,
        Integer learningHorizonHours,
        Integer maxReviewPerDay,
        Integer dayCutoffHour,
        String timeZone
) {
}
