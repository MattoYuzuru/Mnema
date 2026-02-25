package app.mnema.core.review.service;

import app.mnema.core.review.controller.dto.ReviewStatsResponse;
import app.mnema.core.review.repository.ReviewStatsRepository;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewStatsServiceTest {

    @Mock
    ReviewStatsRepository statsRepository;

    @Mock
    ReviewUserCardRepository userCardRepository;

    ReviewStatsService service;

    @BeforeEach
    void setup() {
        service = new ReviewStatsService(statsRepository, userCardRepository);
    }

    @Test
    void stats_returnsNormalizedDataset() {
        UUID userId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 3);
        LocalDate forecastStart = LocalDate.now(ZoneOffset.UTC);

        when(statsRepository.loadOverview(eq(userId), isNull(), any(), any()))
                .thenReturn(new OverviewProjectionStub(10, 6, 2, 5000, 500.0, 430.0));
        when(statsRepository.loadDaily(eq(userId), isNull(), any(), any(), eq("UTC"), eq(0)))
                .thenReturn(List.of(
                        new DailyProjectionStub(LocalDate.of(2026, 1, 1), 4, 3, 1, 1, 2, 0, 1800),
                        new DailyProjectionStub(LocalDate.of(2026, 1, 3), 6, 4, 1, 1, 3, 1, 3200)
                ));
        when(statsRepository.loadHourly(eq(userId), isNull(), any(), any(), eq("UTC")))
                .thenReturn(List.of(new HourlyProjectionStub(5, 3, 1, 410.0)));
        when(statsRepository.loadRatings(eq(userId), isNull(), any(), any()))
                .thenReturn(List.of(
                        new RatingProjectionStub(0, 2),
                        new RatingProjectionStub(1, 1),
                        new RatingProjectionStub(2, 5),
                        new RatingProjectionStub(3, 2)
                ));
        when(statsRepository.loadSources(eq(userId), isNull(), any(), any()))
                .thenReturn(List.of(
                        new SourceProjectionStub("web", 8),
                        new SourceProjectionStub("mobile", 2)
                ));
        when(statsRepository.loadSnapshot(eq(userId), isNull(), any(), any(), any(), any()))
                .thenReturn(new SnapshotProjectionStub(100, 80, 20, 5, 12, 30, 16, 40, 7));
        when(statsRepository.loadForecast(eq(userId), isNull(), any(), any(), eq("UTC"), eq(0)))
                .thenReturn(List.of(
                        new ForecastProjectionStub(forecastStart, 15),
                        new ForecastProjectionStub(forecastStart.plusDays(1), 12)
                ));

        ReviewStatsResponse stats = service.stats(userId, null, from, to, "UTC", 0, 3);

        assertThat(stats.scope()).isEqualTo(ReviewStatsResponse.Scope.ACCOUNT);
        assertThat(stats.overview().reviewCount()).isEqualTo(10);
        assertThat(stats.overview().againRatePercent()).isEqualTo(20.0);
        assertThat(stats.overview().successRatePercent()).isEqualTo(80.0);
        assertThat(stats.daily()).hasSize(3);
        assertThat(stats.daily().get(1).date()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(stats.daily().get(1).reviewCount()).isZero();
        assertThat(stats.hourly()).hasSize(24);
        assertThat(stats.hourly().get(5).reviewCount()).isEqualTo(3);
        assertThat(stats.ratings()).extracting(ReviewStatsResponse.RatingPoint::rating)
                .containsExactly("again", "hard", "good", "easy");
        assertThat(stats.sources()).hasSize(2);
        assertThat(stats.queue().dueNow()).isEqualTo(12);
        assertThat(stats.forecast()).hasSize(3);
        assertThat(stats.forecast().get(0).dueCount()).isEqualTo(15);
        assertThat(stats.forecast().get(2).dueCount()).isZero();
    }

    @Test
    void stats_rejectsDeckOutsideUserScope() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        when(userCardRepository.existsActiveDeck(userId, deckId)).thenReturn(false);

        assertThatThrownBy(() -> service.stats(
                userId,
                deckId,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 10),
                "UTC",
                0,
                30
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User deck not found");
    }

    private static final class OverviewProjectionStub implements ReviewStatsRepository.OverviewProjection {
        private final long reviewCount;
        private final long uniqueCardCount;
        private final long againCount;
        private final long totalResponseMs;
        private final Double avgResponseMs;
        private final Double medianResponseMs;

        private OverviewProjectionStub(long reviewCount,
                                       long uniqueCardCount,
                                       long againCount,
                                       long totalResponseMs,
                                       Double avgResponseMs,
                                       Double medianResponseMs) {
            this.reviewCount = reviewCount;
            this.uniqueCardCount = uniqueCardCount;
            this.againCount = againCount;
            this.totalResponseMs = totalResponseMs;
            this.avgResponseMs = avgResponseMs;
            this.medianResponseMs = medianResponseMs;
        }

        @Override
        public long getReviewCount() {
            return reviewCount;
        }

        @Override
        public long getUniqueCardCount() {
            return uniqueCardCount;
        }

        @Override
        public long getAgainCount() {
            return againCount;
        }

        @Override
        public long getTotalResponseMs() {
            return totalResponseMs;
        }

        @Override
        public Double getAvgResponseMs() {
            return avgResponseMs;
        }

        @Override
        public Double getMedianResponseMs() {
            return medianResponseMs;
        }
    }

    private static final class DailyProjectionStub implements ReviewStatsRepository.DailyProjection {
        private final LocalDate bucketDate;
        private final long reviewCount;
        private final long uniqueCardCount;
        private final long againCount;
        private final long hardCount;
        private final long goodCount;
        private final long easyCount;
        private final long totalResponseMs;

        private DailyProjectionStub(LocalDate bucketDate,
                                    long reviewCount,
                                    long uniqueCardCount,
                                    long againCount,
                                    long hardCount,
                                    long goodCount,
                                    long easyCount,
                                    long totalResponseMs) {
            this.bucketDate = bucketDate;
            this.reviewCount = reviewCount;
            this.uniqueCardCount = uniqueCardCount;
            this.againCount = againCount;
            this.hardCount = hardCount;
            this.goodCount = goodCount;
            this.easyCount = easyCount;
            this.totalResponseMs = totalResponseMs;
        }

        @Override
        public LocalDate getBucketDate() {
            return bucketDate;
        }

        @Override
        public long getReviewCount() {
            return reviewCount;
        }

        @Override
        public long getUniqueCardCount() {
            return uniqueCardCount;
        }

        @Override
        public long getAgainCount() {
            return againCount;
        }

        @Override
        public long getHardCount() {
            return hardCount;
        }

        @Override
        public long getGoodCount() {
            return goodCount;
        }

        @Override
        public long getEasyCount() {
            return easyCount;
        }

        @Override
        public long getTotalResponseMs() {
            return totalResponseMs;
        }
    }

    private static final class HourlyProjectionStub implements ReviewStatsRepository.HourlyProjection {
        private final int hourOfDay;
        private final long reviewCount;
        private final long againCount;
        private final Double avgResponseMs;

        private HourlyProjectionStub(int hourOfDay, long reviewCount, long againCount, Double avgResponseMs) {
            this.hourOfDay = hourOfDay;
            this.reviewCount = reviewCount;
            this.againCount = againCount;
            this.avgResponseMs = avgResponseMs;
        }

        @Override
        public int getHourOfDay() {
            return hourOfDay;
        }

        @Override
        public long getReviewCount() {
            return reviewCount;
        }

        @Override
        public long getAgainCount() {
            return againCount;
        }

        @Override
        public Double getAvgResponseMs() {
            return avgResponseMs;
        }
    }

    private static final class RatingProjectionStub implements ReviewStatsRepository.RatingProjection {
        private final int ratingCode;
        private final long reviewCount;

        private RatingProjectionStub(int ratingCode, long reviewCount) {
            this.ratingCode = ratingCode;
            this.reviewCount = reviewCount;
        }

        @Override
        public int getRatingCode() {
            return ratingCode;
        }

        @Override
        public long getReviewCount() {
            return reviewCount;
        }
    }

    private static final class SourceProjectionStub implements ReviewStatsRepository.SourceProjection {
        private final String source;
        private final long reviewCount;

        private SourceProjectionStub(String source, long reviewCount) {
            this.source = source;
            this.reviewCount = reviewCount;
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public long getReviewCount() {
            return reviewCount;
        }
    }

    private static final class ForecastProjectionStub implements ReviewStatsRepository.ForecastProjection {
        private final LocalDate bucketDate;
        private final long dueCount;

        private ForecastProjectionStub(LocalDate bucketDate, long dueCount) {
            this.bucketDate = bucketDate;
            this.dueCount = dueCount;
        }

        @Override
        public LocalDate getBucketDate() {
            return bucketDate;
        }

        @Override
        public long getDueCount() {
            return dueCount;
        }
    }

    private static final class SnapshotProjectionStub implements ReviewStatsRepository.SnapshotProjection {
        private final long activeCards;
        private final long trackedCards;
        private final long newCards;
        private final long suspendedCards;
        private final long dueNow;
        private final long dueToday;
        private final long dueIn24h;
        private final long dueIn7d;
        private final long overdue;

        private SnapshotProjectionStub(long activeCards,
                                       long trackedCards,
                                       long newCards,
                                       long suspendedCards,
                                       long dueNow,
                                       long dueToday,
                                       long dueIn24h,
                                       long dueIn7d,
                                       long overdue) {
            this.activeCards = activeCards;
            this.trackedCards = trackedCards;
            this.newCards = newCards;
            this.suspendedCards = suspendedCards;
            this.dueNow = dueNow;
            this.dueToday = dueToday;
            this.dueIn24h = dueIn24h;
            this.dueIn7d = dueIn7d;
            this.overdue = overdue;
        }

        @Override
        public long getActiveCards() {
            return activeCards;
        }

        @Override
        public long getTrackedCards() {
            return trackedCards;
        }

        @Override
        public long getNewCards() {
            return newCards;
        }

        @Override
        public long getSuspendedCards() {
            return suspendedCards;
        }

        @Override
        public long getDueNow() {
            return dueNow;
        }

        @Override
        public long getDueToday() {
            return dueToday;
        }

        @Override
        public long getDueInOneDay() {
            return dueIn24h;
        }

        @Override
        public long getDueInSevenDays() {
            return dueIn7d;
        }

        @Override
        public long getOverdue() {
            return overdue;
        }
    }
}
