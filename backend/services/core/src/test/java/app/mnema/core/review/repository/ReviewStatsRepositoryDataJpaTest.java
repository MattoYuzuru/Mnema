package app.mnema.core.review.repository;

import app.mnema.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReviewStatsRepositoryDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    ReviewStatsRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void aggregatesReviewMetricsAndQueueSnapshot() {
        UUID userId = UUID.randomUUID();
        UUID anotherUserId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID anotherDeckId = UUID.randomUUID();
        UUID cardA = UUID.randomUUID();
        UUID cardB = UUID.randomUUID();
        UUID cardC = UUID.randomUUID();
        UUID cardD = UUID.randomUUID();
        UUID outsiderCard = UUID.randomUUID();

        seedDeck(deckId, userId, "Deck A");
        seedDeck(anotherDeckId, anotherUserId, "Deck B");
        seedCard(cardA, userId, deckId);
        seedCard(cardB, userId, deckId);
        seedCard(cardC, userId, deckId);
        seedCard(cardD, userId, deckId);
        seedCard(outsiderCard, anotherUserId, anotherDeckId);

        seedAlgorithm("fsrs_v6");

        Instant now = Instant.now();
        seedState(cardA, "fsrs_v6", now.minusSeconds(7200), false);
        seedState(cardB, "fsrs_v6", now.plusSeconds(20 * 3600L), false);
        seedState(cardD, "fsrs_v6", now.plusSeconds(3600), true);
        seedState(outsiderCard, "fsrs_v6", now.plusSeconds(3600), false);

        // 2026-01-11 03:30 UTC with cutoff=240 should fall into 2026-01-10 bucket.
        seedLog(cardA, "fsrs_v6", Instant.parse("2026-01-10T10:00:00Z"), 0, 1100, "web");
        seedLog(cardB, "fsrs_v6", Instant.parse("2026-01-10T11:00:00Z"), 2, 600, "mobile");
        seedLog(cardA, "fsrs_v6", Instant.parse("2026-01-11T03:30:00Z"), 3, 500, "web");
        seedLog(outsiderCard, "fsrs_v6", Instant.parse("2026-01-10T12:00:00Z"), 0, 900, "web");

        Instant from = Instant.parse("2026-01-10T00:00:00Z");
        Instant to = Instant.parse("2026-01-12T00:00:00Z");

        ReviewStatsRepository.OverviewProjection overview = repository.loadOverview(userId, deckId, from, to);
        assertThat(overview.getReviewCount()).isEqualTo(3);
        assertThat(overview.getAgainCount()).isEqualTo(1);
        assertThat(overview.getUniqueCardCount()).isEqualTo(2);
        assertThat(overview.getTotalResponseMs()).isEqualTo(2200);

        List<ReviewStatsRepository.DailyProjection> daily = repository.loadDaily(
                userId,
                deckId,
                from,
                to,
                "UTC",
                240
        );
        assertThat(daily).hasSize(1);
        assertThat(daily.getFirst().getBucketDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(daily.getFirst().getReviewCount()).isEqualTo(3);

        List<ReviewStatsRepository.RatingProjection> ratings = repository.loadRatings(userId, deckId, from, to);
        assertThat(ratings).hasSize(3);

        List<ReviewStatsRepository.SourceProjection> sources = repository.loadSources(userId, deckId, from, to);
        assertThat(sources).extracting(ReviewStatsRepository.SourceProjection::getSource)
                .containsExactly("web", "mobile");

        ReviewStatsRepository.SnapshotProjection snapshot = repository.loadSnapshot(
                userId,
                deckId,
                now,
                now.plusSeconds(24 * 3600L),
                now.plusSeconds(24 * 3600L),
                now.plusSeconds(7 * 24 * 3600L)
        );
        assertThat(snapshot.getActiveCards()).isEqualTo(4);
        assertThat(snapshot.getTrackedCards()).isEqualTo(3);
        assertThat(snapshot.getNewCards()).isEqualTo(1);
        assertThat(snapshot.getSuspendedCards()).isEqualTo(1);
        assertThat(snapshot.getDueNow()).isEqualTo(1);
        assertThat(snapshot.getDueInOneDay()).isEqualTo(2);
        assertThat(snapshot.getOverdue()).isEqualTo(1);

        List<ReviewStatsRepository.ForecastProjection> forecast = repository.loadForecast(
                userId,
                deckId,
                now,
                now.plusSeconds(3 * 24 * 3600L),
                "UTC",
                0
        );
        long forecastTotal = forecast.stream().mapToLong(ReviewStatsRepository.ForecastProjection::getDueCount).sum();
        assertThat(forecastTotal).isEqualTo(1);

        ReviewStatsRepository.StreakProjection streak = repository.loadStreak(
                userId,
                deckId,
                now,
                "UTC",
                240
        );
        assertThat(streak.getCurrentStreakDays()).isEqualTo(1);
        assertThat(streak.getLongestStreakDays()).isEqualTo(1);
        assertThat(streak.getTodayStreakDays()).isEqualTo(0);
        assertThat(streak.getActiveToday()).isFalse();

        List<ReviewStatsRepository.SessionDayProjection> sessionDays = repository.loadSessionDays(
                userId,
                deckId,
                from,
                to,
                "UTC",
                240,
                30
        );
        assertThat(sessionDays).hasSize(1);
        assertThat(sessionDays.getFirst().getBucketDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(sessionDays.getFirst().getSessionCount()).isEqualTo(3);
        assertThat(sessionDays.getFirst().getStudiedMinutes()).isEqualTo(3);
        assertThat(sessionDays.getFirst().getReviewCount()).isEqualTo(3);

        List<ReviewStatsRepository.SessionWindowProjection> sessionWindows = repository.loadSessionWindows(
                userId,
                deckId,
                from,
                to,
                "UTC",
                240,
                30
        );
        assertThat(sessionWindows).hasSize(3);
        assertThat(sessionWindows.getFirst().getDurationMinutes()).isEqualTo(1);

        ReviewStatsRepository.SessionWindowProjection latestSession = repository.loadLatestSessionWindow(
                userId,
                deckId,
                from,
                to,
                "UTC",
                240,
                30
        );
        assertThat(latestSession).isNotNull();
        assertThat(latestSession.getReviewCount()).isEqualTo(1);
    }

    private void seedAlgorithm(String algorithmId) {
        jdbcTemplate.update(
                """
                        insert into app_core.sr_algorithms (algorithm_id, name)
                        values (?, ?)
                        on conflict (algorithm_id) do nothing
                        """,
                algorithmId,
                algorithmId
        );
    }

    private void seedDeck(UUID deckId, UUID userId, String name) {
        jdbcTemplate.update(
                """
                        insert into app_core.user_decks (
                            user_deck_id, user_id, display_name, auto_update, is_archived, template_version, subscribed_template_version
                        ) values (?, ?, ?, true, false, 1, 1)
                        """,
                deckId,
                userId,
                name
        );
    }

    private void seedCard(UUID cardId, UUID userId, UUID deckId) {
        jdbcTemplate.update(
                """
                        insert into app_core.user_cards (
                            user_card_id, user_id, subscription_id, is_custom, is_deleted, created_at
                        ) values (?, ?, ?, true, false, now())
                        """,
                cardId,
                userId,
                deckId
        );
    }

    private void seedState(UUID cardId, String algorithmId, Instant nextReviewAt, boolean suspended) {
        jdbcTemplate.update(
                """
                        insert into app_core.sr_card_states (
                            user_card_id, algorithm_id, state, last_review_at, next_review_at, review_count, is_suspended, row_version
                        ) values (?, ?, '{}'::jsonb, now(), ?, 1, ?, 0)
                        """,
                cardId,
                algorithmId,
                Timestamp.from(nextReviewAt),
                suspended
        );
    }

    private void seedLog(UUID cardId,
                         String algorithmId,
                         Instant reviewedAt,
                         int rating,
                         int responseMs,
                         String source) {
        jdbcTemplate.update(
                """
                        insert into app_core.sr_review_logs (
                            user_card_id, algorithm_id, reviewed_at, rating, response_ms, source, state_before, state_after
                        ) values (?, ?, ?, ?, ?, ?::review_source, '{}'::jsonb, '{}'::jsonb)
                        """,
                cardId,
                algorithmId,
                Timestamp.from(reviewedAt),
                rating,
                responseMs,
                source
        );
    }
}
