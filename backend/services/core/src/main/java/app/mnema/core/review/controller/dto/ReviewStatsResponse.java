package app.mnema.core.review.controller.dto;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewStatsResponse(
        Scope scope,
        UUID userDeckId,
        Filter filter,
        Streak streak,
        Overview overview,
        QueueSnapshot queue,
        List<DailyPoint> daily,
        List<SessionDayPoint> sessionDays,
        List<SessionWindowPoint> todaySessions,
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
            int sessionGapMinutes,
            int forecastDays
    ) {
    }

    public record Streak(
            long currentStreakDays,
            long longestStreakDays,
            long todayStreakDays,
            boolean activeToday,
            LocalDate currentStreakStartDate,
            LocalDate currentStreakEndDate,
            LocalDate lastActiveDate
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

    public record SessionDayPoint(
            LocalDate date,
            long sessionCount,
            Instant firstSessionStartAt,
            Instant lastSessionEndAt,
            long studiedMinutes,
            long reviewCount,
            long totalResponseMs
    ) {
    }

    public record SessionWindowPoint(
            Instant startedAt,
            Instant endedAt,
            long durationMinutes,
            long reviewCount,
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
