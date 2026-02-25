package app.mnema.core.review.controller.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReviewStatsResponse(
        Scope scope,
        UUID userDeckId,
        Filter filter,
        Overview overview,
        QueueSnapshot queue,
        List<DailyPoint> daily,
        List<HourlyPoint> hourly,
        List<RatingPoint> ratings,
        List<SourcePoint> sources,
        List<ForecastPoint> forecast
) {
    public enum Scope {
        ACCOUNT,
        DECK
    }

    public record Filter(
            LocalDate fromDate,
            LocalDate toDate,
            String timeZone,
            int dayCutoffMinutes,
            int forecastDays
    ) {
    }

    public record Overview(
            long reviewCount,
            long uniqueCardCount,
            long againCount,
            double againRatePercent,
            double successRatePercent,
            long totalResponseMs,
            int avgResponseMs,
            int medianResponseMs,
            double reviewsPerDay
    ) {
    }

    public record QueueSnapshot(
            long activeCards,
            long trackedCards,
            long newCards,
            long suspendedCards,
            long dueNow,
            long dueToday,
            long dueIn24h,
            long dueIn7d,
            long overdue
    ) {
    }

    public record DailyPoint(
            LocalDate date,
            long reviewCount,
            long uniqueCardCount,
            long againCount,
            long hardCount,
            long goodCount,
            long easyCount,
            long totalResponseMs
    ) {
    }

    public record HourlyPoint(
            int hourOfDay,
            long reviewCount,
            double againRatePercent,
            int avgResponseMs
    ) {
    }

    public record RatingPoint(
            int ratingCode,
            String rating,
            long reviewCount,
            double ratioPercent
    ) {
    }

    public record SourcePoint(
            String source,
            long reviewCount,
            double ratioPercent
    ) {
    }

    public record ForecastPoint(
            LocalDate date,
            long dueCount
    ) {
    }
}
