package app.mnema.core.review.service;

import app.mnema.core.review.entity.UserDeckPreferencesEntity;
import app.mnema.core.review.repository.UserDeckPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

        ArgumentCaptor<UserDeckPreferencesEntity> captor = ArgumentCaptor.forClass(UserDeckPreferencesEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getUserDeckId()).isEqualTo(deckId);
    }

    @Test
    void incrementCounters_resetsWhenDateChanges() {
        UUID deckId = UUID.randomUUID();
        UserDeckPreferencesEntity entity = new UserDeckPreferencesEntity();
        entity.setUserDeckId(deckId);
        entity.setLearningHorizonMinutes(1440);
        entity.setMaxNewPerDay(20);
        entity.setDayCutoffMinutes(0);
        entity.setTimeZoneId("UTC");
        entity.setNewSeenToday(5);
        entity.setCounterDate(LocalDate.of(2024, 1, 1));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());

        when(repository.findByUserDeckId(deckId)).thenReturn(Optional.of(entity));
        when(repository.save(any(UserDeckPreferencesEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.incrementCounters(deckId, true, Instant.parse("2024-01-02T05:00:00Z"));

        assertThat(entity.getCounterDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(entity.getNewSeenToday()).isEqualTo(1);
        verify(repository, atLeastOnce()).save(entity);
    }
}
