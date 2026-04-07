package app.mnema.core.review.service;

import app.mnema.core.review.entity.UserDeckPreferencesEntity;
import app.mnema.core.review.repository.UserDeckPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeckPreferencesServiceTest {

    @Mock
    UserDeckPreferencesRepository repository;

    UserDeckPreferencesService service;

    @BeforeEach
    void setup() {
        service = new UserDeckPreferencesService(repository);
    }

    @Test
    void getSnapshot_createsDefaultsWhenMissing() {
        UUID deckId = UUID.randomUUID();
        when(repository.findById(deckId)).thenReturn(Optional.empty());
        when(repository.save(any(UserDeckPreferencesEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDeckPreferencesService.PreferencesSnapshot snapshot = service.getSnapshot(deckId, Instant.parse("2024-01-01T00:00:00Z"));

        assertThat(snapshot.userDeckId()).isEqualTo(deckId);
        assertThat(snapshot.learningHorizon().toMinutes()).isEqualTo(120);
        assertThat(snapshot.maxNewPerDay()).isEqualTo(20);
        assertThat(snapshot.maxReviewPerDay()).isNull();
        assertThat(snapshot.dayCutoffMinutes()).isZero();
        assertThat(snapshot.timeZoneId()).isNull();

        ArgumentCaptor<UserDeckPreferencesEntity> captor = ArgumentCaptor.forClass(UserDeckPreferencesEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getUserDeckId()).isEqualTo(deckId);
    }

    @Test
    void getSnapshot_resetsCountersWhenReviewDayChanges() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity entity = entity(deckId);
        entity.setTimeZoneId("UTC");
        entity.setDayCutoffMinutes(60);
        entity.setCounterDate(LocalDate.of(2024, 1, 1));
        entity.setNewSeenToday(7);
        entity.setReviewSeenToday(9);

        when(repository.findById(deckId)).thenReturn(Optional.of(entity));
        when(repository.save(any(UserDeckPreferencesEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDeckPreferencesService.PreferencesSnapshot snapshot = service.getSnapshot(deckId, Instant.parse("2024-01-02T02:30:00Z"));

        assertThat(snapshot.newSeenToday()).isZero();
        assertThat(snapshot.reviewSeenToday()).isZero();
        assertThat(snapshot.reviewDay(Instant.parse("2024-01-02T00:30:00Z")).date()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(entity.getCounterDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        verify(repository).save(entity);
    }

    @Test
    void getSnapshot_fallsBackToUtcWhenTimezoneIsInvalid() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity entity = entity(deckId);
        entity.setTimeZoneId("Broken/Zone");
        entity.setDayCutoffMinutes(1500);
        entity.setCounterDate(LocalDate.of(2026, 4, 6));

        when(repository.findById(deckId)).thenReturn(Optional.of(entity));

        UserDeckPreferencesService.PreferencesSnapshot snapshot = service.getSnapshot(deckId, Instant.parse("2026-04-07T00:30:00Z"));

        assertThat(snapshot.reviewDay(Instant.parse("2026-04-07T00:30:00Z")).date()).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(snapshot.dayCutoffMinutes()).isEqualTo(1439);
        verify(repository, never()).save(entity);
    }

    @Test
    void incrementCounters_resetsWhenDateChangesAndTracksNewCards() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity entity = entity(deckId);
        entity.setTimeZoneId("UTC");
        entity.setCounterDate(LocalDate.of(2024, 1, 1));
        entity.setNewSeenToday(5);
        entity.setReviewSeenToday(3);

        when(repository.findByUserDeckId(deckId)).thenReturn(Optional.of(entity));
        when(repository.save(any(UserDeckPreferencesEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.incrementCounters(deckId, true, Instant.parse("2024-01-02T05:00:00Z"));

        assertThat(entity.getCounterDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(entity.getNewSeenToday()).isEqualTo(1);
        assertThat(entity.getReviewSeenToday()).isZero();
        verify(repository).save(entity);
    }

    @Test
    void incrementCounters_tracksReviewAnswersWithinSameDay() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity entity = entity(deckId);
        entity.setTimeZoneId("Europe/Moscow");
        entity.setDayCutoffMinutes(180);
        entity.setCounterDate(LocalDate.of(2026, 4, 7));
        entity.setNewSeenToday(2);
        entity.setReviewSeenToday(4);

        when(repository.findByUserDeckId(deckId)).thenReturn(Optional.of(entity));
        when(repository.save(any(UserDeckPreferencesEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.incrementCounters(deckId, false, Instant.parse("2026-04-07T10:00:00Z"));

        assertThat(entity.getCounterDate()).isEqualTo(LocalDate.of(2026, 4, 7));
        assertThat(entity.getNewSeenToday()).isEqualTo(2);
        assertThat(entity.getReviewSeenToday()).isEqualTo(5);
    }

    @Test
    void updatePreferences_normalizesValuesAndClearsBlankTimezone() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity entity = entity(deckId);
        entity.setTimeZoneId("UTC");

        when(repository.findByUserDeckId(deckId)).thenReturn(Optional.of(entity));
        when(repository.save(any(UserDeckPreferencesEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckPreferencesService.PreferencesSnapshot snapshot = service.updatePreferences(
                deckId,
                -5,
                0,
                -2,
                42,
                "   "
        );

        assertThat(entity.getMaxNewPerDay()).isZero();
        assertThat(entity.getLearningHorizonMinutes()).isEqualTo(60);
        assertThat(entity.getMaxReviewPerDay()).isZero();
        assertThat(entity.getDayCutoffMinutes()).isEqualTo(23 * 60);
        assertThat(entity.getTimeZoneId()).isNull();
        assertThat(snapshot.maxNewPerDay()).isZero();
        assertThat(snapshot.learningHorizon().toMinutes()).isEqualTo(60);
        verify(repository).save(entity);
    }

    @Test
    void updatePreferences_ignoresInvalidTimezoneWithoutSaving() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity entity = entity(deckId);
        entity.setTimeZoneId("UTC");

        when(repository.findByUserDeckId(deckId)).thenReturn(Optional.of(entity));

        UserDeckPreferencesService.PreferencesSnapshot snapshot = service.updatePreferences(
                deckId,
                null,
                null,
                null,
                null,
                "Broken/Zone"
        );

        assertThat(snapshot.timeZoneId()).isEqualTo("UTC");
        verify(repository, never()).save(any());
    }

    @Test
    void getSnapshot_recoversFromConcurrentDefaultInsert() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity existing = entity(deckId);

        when(repository.findById(deckId)).thenReturn(Optional.empty(), Optional.of(existing));
        when(repository.save(any(UserDeckPreferencesEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserDeckPreferencesService.PreferencesSnapshot snapshot = service.getSnapshot(deckId, Instant.parse("2026-04-07T00:00:00Z"));

        assertThat(snapshot.userDeckId()).isEqualTo(deckId);
        assertThat(snapshot.learningHorizon().toMinutes()).isEqualTo(existing.getLearningHorizonMinutes());
        verify(repository, atLeastOnce()).save(any(UserDeckPreferencesEntity.class));
    }

    @Test
    void preferencesSnapshot_treatsZeroLimitsAsUnlimited() {
        UserDeckPreferencesService.PreferencesSnapshot snapshot = new UserDeckPreferencesService.PreferencesSnapshot(
                UUID.randomUUID(),
                java.time.Duration.ofHours(2),
                0,
                0,
                100,
                100,
                "UTC",
                0
        );

        assertThat(snapshot.remainingNewQuota()).isEqualTo(Long.MAX_VALUE);
        assertThat(snapshot.remainingReviewQuota()).isEqualTo(Long.MAX_VALUE);
        assertThat(snapshot.reviewDay(Instant.parse("2026-04-07T00:00:00Z")).start())
                .isEqualTo(LocalDate.of(2026, 4, 7).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private static UserDeckPreferencesEntity entity(UUID deckId) {
        UserDeckPreferencesEntity entity = new UserDeckPreferencesEntity();
        entity.setUserDeckId(deckId);
        entity.setLearningHorizonMinutes(120);
        entity.setMaxNewPerDay(20);
        entity.setMaxReviewPerDay(null);
        entity.setDayCutoffMinutes(0);
        entity.setTimeZoneId(null);
        entity.setNewSeenToday(0);
        entity.setReviewSeenToday(0);
        entity.setCounterDate(LocalDate.of(2024, 1, 1));
        entity.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return entity;
    }
}
