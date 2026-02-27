package app.mnema.core.review.service;

import app.mnema.core.review.controller.dto.ReviewStatsResponse;
import app.mnema.core.review.repository.ReviewStatsRepository;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ReviewStatsService {

    private static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 366;
    private static final int DEFAULT_FORECAST_DAYS = 30;
    private static final int MAX_FORECAST_DAYS = 365;
    private static final int MAX_DAY_CUTOFF_MINUTES = 24 * 60 - 1;
    private static final int DEFAULT_SESSION_GAP_MINUTES = 30;
    private static final int MAX_SESSION_GAP_MINUTES = 240;

    private static final List<Integer> RATING_CODES = List.of(0, 1, 2, 3);

    private final ReviewStatsRepository statsRepository;
    private final ReviewUserCardRepository userCardRepository;

    public ReviewStatsService(ReviewStatsRepository statsRepository,
                              ReviewUserCardRepository userCardRepository) {
        this.statsRepository = statsRepository;
        this.userCardRepository = userCardRepository;
    }

    @Transactional(readOnly = true)
    public ReviewStatsResponse stats(UUID userId,
                                     UUID userDeckId,
                                     LocalDate fromDate,
                                     LocalDate toDate,
                                     String timeZone,
                                     Integer dayCutoffMinutes,
                                     Integer sessionGapMinutes,
                                     Integer forecastDays) {
        Instant now = Instant.now();
        if (userDeckId != null && !userCardRepository.existsActiveDeck(userId, userDeckId)) {
            throw new IllegalArgumentException("User deck not found: " + userDeckId);
        }
        QueryWindow window = normalizeWindow(now, userDeckId, fromDate, toDate, timeZone, dayCutoffMinutes, sessionGapMinutes, forecastDays);

        ReviewStatsRepository.OverviewProjection overview = statsRepository.loadOverview(
                userId,
                userDeckId,
                window.fromInstant(),
                window.toInstantExclusive()
        );

        long reviewCount = readLong(overview == null ? null : overview.getReviewCount());
        long againCount = readLong(overview == null ? null : overview.getAgainCount());
        long uniqueCardCount = readLong(overview == null ? null : overview.getUniqueCardCount());
        long totalResponseMs = readLong(overview == null ? null : overview.getTotalResponseMs());
        int avgResponseMs = roundToInt(overview == null ? null : overview.getAvgResponseMs());
        int medianResponseMs = roundToInt(overview == null ? null : overview.getMedianResponseMs());
        double againRatePercent = percent(againCount, reviewCount);
        double successRatePercent = percent(Math.max(0, reviewCount - againCount), reviewCount);

        long spanDays = ChronoUnit.DAYS.between(window.fromDate(), window.toDate()) + 1;
        double reviewsPerDay = spanDays <= 0 ? 0.0 : round2((double) reviewCount / spanDays);

        List<ReviewStatsResponse.DailyPoint> daily = buildDaily(
                window,
                statsRepository.loadDaily(
                        userId,
                        userDeckId,
                        window.fromInstant(),
                        window.toInstantExclusive(),
                        window.timeZone().getId(),
                        window.dayCutoffMinutes()
                )
        );

        ReviewStatsResponse.Streak streak = buildStreak(
                statsRepository.loadStreak(
                        userId,
                        userDeckId,
                        now,
                        window.timeZone().getId(),
                        window.dayCutoffMinutes()
                )
        );

        List<ReviewStatsResponse.SessionDayPoint> sessionDays = buildSessionDays(
                window,
                statsRepository.loadSessionDays(
                        userId,
                        userDeckId,
                        window.fromInstant(),
                        window.toInstantExclusive(),
                        window.timeZone().getId(),
                        window.dayCutoffMinutes(),
                        window.sessionGapMinutes()
                )
        );

        List<ReviewStatsResponse.SessionWindowPoint> todaySessions = buildSessionWindows(
                statsRepository.loadSessionWindows(
                        userId,
                        userDeckId,
                        window.reviewDayStartInstant(now),
                        window.reviewDayEndInstant(now),
                        window.timeZone().getId(),
                        window.dayCutoffMinutes(),
                        window.sessionGapMinutes()
                )
        );

        List<ReviewStatsResponse.HourlyPoint> hourly = buildHourly(
                statsRepository.loadHourly(
                        userId,
                        userDeckId,
                        window.fromInstant(),
                        window.toInstantExclusive(),
                        window.timeZone().getId()
                )
        );

        List<ReviewStatsResponse.RatingPoint> ratings = buildRatings(
                reviewCount,
                statsRepository.loadRatings(
                        userId,
                        userDeckId,
                        window.fromInstant(),
                        window.toInstantExclusive()
                )
        );

        List<ReviewStatsResponse.SourcePoint> sources = buildSources(
                reviewCount,
                statsRepository.loadSources(
                        userId,
                        userDeckId,
                        window.fromInstant(),
                        window.toInstantExclusive()
                )
        );

        ReviewStatsRepository.SnapshotProjection snapshot = statsRepository.loadSnapshot(
                userId,
                userDeckId,
                now,
                window.reviewDayEndInstant(now),
                now.plus(Duration.ofHours(24)),
                now.plus(Duration.ofDays(7))
        );

        ReviewStatsResponse.QueueSnapshot queue = new ReviewStatsResponse.QueueSnapshot(
                readLong(snapshot == null ? null : snapshot.getActiveCards()),
                readLong(snapshot == null ? null : snapshot.getTrackedCards()),
                readLong(snapshot == null ? null : snapshot.getNewCards()),
                readLong(snapshot == null ? null : snapshot.getSuspendedCards()),
                readLong(snapshot == null ? null : snapshot.getDueNow()),
                readLong(snapshot == null ? null : snapshot.getDueToday()),
                readLong(snapshot == null ? null : snapshot.getDueInOneDay()),
                readLong(snapshot == null ? null : snapshot.getDueInSevenDays()),
                readLong(snapshot == null ? null : snapshot.getOverdue())
        );

        List<ReviewStatsResponse.ForecastPoint> forecast = buildForecast(
                window,
                statsRepository.loadForecast(
                        userId,
                        userDeckId,
                        window.forecastStartInstant(now),
                        window.forecastEndInstant(now),
                        window.timeZone().getId(),
                        window.dayCutoffMinutes()
                ),
                now
        );

        ReviewStatsResponse.Overview overviewDto = new ReviewStatsResponse.Overview(
                reviewCount,
                uniqueCardCount,
                againCount,
                againRatePercent,
                successRatePercent,
                totalResponseMs,
                avgResponseMs,
                medianResponseMs,
                reviewsPerDay
        );

        ReviewStatsResponse.Filter filter = new ReviewStatsResponse.Filter(
                window.fromDate(),
                window.toDate(),
                window.timeZone().getId(),
                window.dayCutoffMinutes(),
                window.sessionGapMinutes(),
                window.forecastDays()
        );

        ReviewStatsResponse.Scope scope = userDeckId == null
                ? ReviewStatsResponse.Scope.ACCOUNT
                : ReviewStatsResponse.Scope.DECK;

        return new ReviewStatsResponse(
                scope,
                userDeckId,
                filter,
                streak,
                overviewDto,
                queue,
                daily,
                sessionDays,
                todaySessions,
                hourly,
                ratings,
                sources,
                forecast
        );
    }

    private static List<ReviewStatsResponse.DailyPoint> buildDaily(QueryWindow window,
                                                                   List<ReviewStatsRepository.DailyProjection> rows) {
        Map<LocalDate, ReviewStatsRepository.DailyProjection> byDate = new HashMap<>();
        for (ReviewStatsRepository.DailyProjection row : rows) {
            byDate.put(row.getBucketDate(), row);
        }

        List<ReviewStatsResponse.DailyPoint> out = new ArrayList<>();
        LocalDate cursor = window.fromDate();
        while (!cursor.isAfter(window.toDate())) {
            ReviewStatsRepository.DailyProjection row = byDate.get(cursor);
            out.add(new ReviewStatsResponse.DailyPoint(
                    cursor,
                    readLong(row == null ? null : row.getReviewCount()),
                    readLong(row == null ? null : row.getUniqueCardCount()),
                    readLong(row == null ? null : row.getAgainCount()),
                    readLong(row == null ? null : row.getHardCount()),
                    readLong(row == null ? null : row.getGoodCount()),
                    readLong(row == null ? null : row.getEasyCount()),
                    readLong(row == null ? null : row.getTotalResponseMs())
            ));
            cursor = cursor.plusDays(1);
        }
        return out;
    }

    private static List<ReviewStatsResponse.HourlyPoint> buildHourly(List<ReviewStatsRepository.HourlyProjection> rows) {
        Map<Integer, ReviewStatsRepository.HourlyProjection> byHour = new HashMap<>();
        for (ReviewStatsRepository.HourlyProjection row : rows) {
            byHour.put(row.getHourOfDay(), row);
        }

        List<ReviewStatsResponse.HourlyPoint> out = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            ReviewStatsRepository.HourlyProjection row = byHour.get(hour);
            long count = readLong(row == null ? null : row.getReviewCount());
            long again = readLong(row == null ? null : row.getAgainCount());
            out.add(new ReviewStatsResponse.HourlyPoint(
                    hour,
                    count,
                    percent(again, count),
                    roundToInt(row == null ? null : row.getAvgResponseMs())
            ));
        }
        return out;
    }

    private static ReviewStatsResponse.Streak buildStreak(ReviewStatsRepository.StreakProjection projection) {
        if (projection == null) {
            return new ReviewStatsResponse.Streak(0, 0, 0, false, null, null, null);
        }
        return new ReviewStatsResponse.Streak(
                readLong(projection.getCurrentStreakDays()),
                readLong(projection.getLongestStreakDays()),
                readLong(projection.getTodayStreakDays()),
                projection.getActiveToday(),
                projection.getCurrentStreakStartDate(),
                projection.getCurrentStreakEndDate(),
                projection.getLastActiveDate()
        );
    }

    private static List<ReviewStatsResponse.SessionDayPoint> buildSessionDays(QueryWindow window,
                                                                               List<ReviewStatsRepository.SessionDayProjection> rows) {
        Map<LocalDate, ReviewStatsRepository.SessionDayProjection> byDate = new HashMap<>();
        for (ReviewStatsRepository.SessionDayProjection row : rows) {
            byDate.put(row.getBucketDate(), row);
        }

        List<ReviewStatsResponse.SessionDayPoint> out = new ArrayList<>();
        LocalDate cursor = window.fromDate();
        while (!cursor.isAfter(window.toDate())) {
            ReviewStatsRepository.SessionDayProjection row = byDate.get(cursor);
            out.add(new ReviewStatsResponse.SessionDayPoint(
                    cursor,
                    readLong(row == null ? null : row.getSessionCount()),
                    row == null ? null : row.getFirstSessionStartAt(),
                    row == null ? null : row.getLastSessionEndAt(),
                    readLong(row == null ? null : row.getStudiedMinutes()),
                    readLong(row == null ? null : row.getReviewCount()),
                    readLong(row == null ? null : row.getTotalResponseMs())
            ));
            cursor = cursor.plusDays(1);
        }
        return out;
    }

    private static List<ReviewStatsResponse.SessionWindowPoint> buildSessionWindows(List<ReviewStatsRepository.SessionWindowProjection> rows) {
        List<ReviewStatsResponse.SessionWindowPoint> out = new ArrayList<>(rows.size());
        for (ReviewStatsRepository.SessionWindowProjection row : rows) {
            out.add(new ReviewStatsResponse.SessionWindowPoint(
                    row.getSessionStartedAt(),
                    row.getSessionEndedAt(),
                    readLong(row.getDurationMinutes()),
                    readLong(row.getReviewCount()),
                    readLong(row.getTotalResponseMs())
            ));
        }
        return out;
    }

    private static List<ReviewStatsResponse.RatingPoint> buildRatings(long totalReviews,
                                                                      List<ReviewStatsRepository.RatingProjection> rows) {
        Map<Integer, Long> counts = new HashMap<>();
        for (ReviewStatsRepository.RatingProjection row : rows) {
            counts.put(row.getRatingCode(), row.getReviewCount());
        }

        List<ReviewStatsResponse.RatingPoint> out = new ArrayList<>(RATING_CODES.size());
        for (int code : RATING_CODES) {
            long count = counts.getOrDefault(code, 0L);
            out.add(new ReviewStatsResponse.RatingPoint(
                    code,
                    ratingName(code),
                    count,
                    percent(count, totalReviews)
            ));
        }
        return out;
    }

    private static List<ReviewStatsResponse.SourcePoint> buildSources(long totalReviews,
                                                                      List<ReviewStatsRepository.SourceProjection> rows) {
        List<ReviewStatsResponse.SourcePoint> out = new ArrayList<>(rows.size());
        for (ReviewStatsRepository.SourceProjection row : rows) {
            long count = readLong(row.getReviewCount());
            out.add(new ReviewStatsResponse.SourcePoint(
                    normalizeSource(row.getSource()),
                    count,
                    percent(count, totalReviews)
            ));
        }
        return out;
    }

    private static List<ReviewStatsResponse.ForecastPoint> buildForecast(QueryWindow window,
                                                                         List<ReviewStatsRepository.ForecastProjection> rows,
                                                                         Instant now) {
        Map<LocalDate, Long> byDate = new HashMap<>();
        for (ReviewStatsRepository.ForecastProjection row : rows) {
            byDate.put(row.getBucketDate(), row.getDueCount());
        }

        LocalDate startDate = reviewDayDate(now, window.timeZone(), window.dayCutoffMinutes());
        List<ReviewStatsResponse.ForecastPoint> out = new ArrayList<>(window.forecastDays());
        for (int i = 0; i < window.forecastDays(); i++) {
            LocalDate date = startDate.plusDays(i);
            out.add(new ReviewStatsResponse.ForecastPoint(date, byDate.getOrDefault(date, 0L)));
        }
        return out;
    }

    private static QueryWindow normalizeWindow(Instant now,
                                               UUID userDeckId,
                                               LocalDate fromDate,
                                               LocalDate toDate,
                                               String timeZone,
                                               Integer dayCutoffMinutes,
                                               Integer sessionGapMinutes,
                                               Integer forecastDays) {
        ZoneId zone = normalizeZone(timeZone);
        int cutoffMinutes = normalizeDayCutoff(dayCutoffMinutes);
        int gapMinutes = normalizeSessionGapMinutes(sessionGapMinutes);
        int forecast = normalizeForecastDays(forecastDays);

        LocalDate defaultTo = reviewDayDate(now, zone, cutoffMinutes);
        LocalDate resolvedTo = toDate == null ? defaultTo : toDate;
        LocalDate resolvedFrom = fromDate == null ? resolvedTo.minusDays(DEFAULT_RANGE_DAYS - 1L) : fromDate;
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be <= to");
        }

        long rangeDays = ChronoUnit.DAYS.between(resolvedFrom, resolvedTo) + 1;
        if (rangeDays > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("date range is too large");
        }

        Instant fromInstant = dayStartInstant(resolvedFrom, zone, cutoffMinutes);
        Instant toInstantExclusive = dayStartInstant(resolvedTo.plusDays(1), zone, cutoffMinutes);
        return new QueryWindow(
                userDeckId,
                zone,
                cutoffMinutes,
                gapMinutes,
                resolvedFrom,
                resolvedTo,
                fromInstant,
                toInstantExclusive,
                forecast
        );
    }

    private static ZoneId normalizeZone(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Unknown time zone: " + value);
        }
    }

    private static int normalizeDayCutoff(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > MAX_DAY_CUTOFF_MINUTES) {
            throw new IllegalArgumentException("dayCutoffMinutes must be in range [0, 1439]");
        }
        return value;
    }

    private static int normalizeForecastDays(Integer value) {
        if (value == null) {
            return DEFAULT_FORECAST_DAYS;
        }
        if (value < 1 || value > MAX_FORECAST_DAYS) {
            throw new IllegalArgumentException("forecastDays must be in range [1, 365]");
        }
        return value;
    }

    private static int normalizeSessionGapMinutes(Integer value) {
        if (value == null) {
            return DEFAULT_SESSION_GAP_MINUTES;
        }
        if (value < 5 || value > MAX_SESSION_GAP_MINUTES) {
            throw new IllegalArgumentException("sessionGapMinutes must be in range [5, 240]");
        }
        return value;
    }

    private static String ratingName(int code) {
        return switch (code) {
            case 0 -> "again";
            case 1 -> "hard";
            case 2 -> "good";
            case 3 -> "easy";
            default -> "unknown";
        };
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        return source.toLowerCase(Locale.ROOT);
    }

    private static Instant dayStartInstant(LocalDate date, ZoneId zoneId, int dayCutoffMinutes) {
        LocalTime cutoff = LocalTime.of(dayCutoffMinutes / 60, dayCutoffMinutes % 60);
        return date.atTime(cutoff).atZone(zoneId).toInstant();
    }

    private static LocalDate reviewDayDate(Instant now, ZoneId zoneId, int dayCutoffMinutes) {
        LocalTime cutoff = LocalTime.of(dayCutoffMinutes / 60, dayCutoffMinutes % 60);
        ZonedDateTime zonedNow = now.atZone(zoneId);
        LocalDate date = zonedNow.toLocalDate();
        if (dayCutoffMinutes > 0 && zonedNow.toLocalTime().isBefore(cutoff)) {
            return date.minusDays(1);
        }
        return date;
    }

    private static long readLong(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private static int roundToInt(Double value) {
        return value == null ? 0 : (int) Math.round(value);
    }

    private static double percent(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return round2((part * 100.0) / total);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record QueryWindow(
            UUID userDeckId,
            ZoneId timeZone,
            int dayCutoffMinutes,
            int sessionGapMinutes,
            LocalDate fromDate,
            LocalDate toDate,
            Instant fromInstant,
            Instant toInstantExclusive,
            int forecastDays
    ) {
        LocalDate reviewDayDate(Instant now) {
            return ReviewStatsService.reviewDayDate(now, timeZone, dayCutoffMinutes);
        }

        Instant reviewDayStartInstant(Instant now) {
            LocalDate day = reviewDayDate(now);
            return dayStartInstant(day, timeZone, dayCutoffMinutes);
        }

        Instant reviewDayEndInstant(Instant now) {
            LocalDate day = reviewDayDate(now);
            return dayStartInstant(day.plusDays(1), timeZone, dayCutoffMinutes);
        }

        Instant forecastStartInstant(Instant now) {
            LocalDate day = reviewDayDate(now);
            return dayStartInstant(day, timeZone, dayCutoffMinutes);
        }

        Instant forecastEndInstant(Instant now) {
            LocalDate day = reviewDayDate(now);
            return dayStartInstant(day.plusDays(forecastDays), timeZone, dayCutoffMinutes);
        }
    }
}
