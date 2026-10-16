package app.mnema.learning.onboarding;

import app.mnema.learning.experiment.ExperimentAssignments;
import app.mnema.learning.experiment.ExperimentEvents;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The holdout decision made when the row is created: only the experiment's {@code control} is held out, and a metric never fails the read. */
class OnboardingCohortTest {
    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
    private final UUID account = UUID.randomUUID();
    private final OnboardingRepository repository = mock(OnboardingRepository.class);
    private final ExperimentAssignments assignments = mock(ExperimentAssignments.class);
    private final ExperimentEvents events = mock(ExperimentEvents.class);
    private OnboardingService service;

    @BeforeEach
    void service() {
        when(repository.now()).thenReturn(NOW);
        when(repository.hasLiveDeck(account)).thenReturn(false);
        when(repository.insert(eq(account), any(), eq(NOW))).thenReturn(true);
        service = new OnboardingService(repository, new OnboardingSettings(true, "2026-10-16T00:00:00Z"), assignments, events, mock(CommandReceiptService.class),
                new SimpleMeterRegistry());
    }

    private OnboardingRepository.Row created(OnboardingRepository.Cohort cohort) {
        return new OnboardingRepository.Row(1, JourneyState.OFFERED, cohort, null, null, null, null, NOW, null, 0, 0);
    }

    private OnboardingRepository.Cohort offeredCohort(Optional<String> variant, OnboardingRepository.Cohort stored) {
        when(assignments.assigned(account, "onboarding_v1")).thenReturn(variant);
        when(repository.find(account)).thenReturn(Optional.empty()).thenReturn(Optional.of(created(stored)));
        ObjectNode view = service.read(account, true);
        ArgumentCaptor<OnboardingRepository.Row> row = ArgumentCaptor.forClass(OnboardingRepository.Row.class);
        verify(repository).insert(eq(account), row.capture(), eq(NOW));
        assertThat(view.path("journey").path("state").stringValue()).isEqualTo("OFFERED");
        return row.getValue().cohort();
    }

    @Test
    void controlIsHeldOutAndCountsItsExposure() {
        assertThat(offeredCohort(Optional.of("control"), OnboardingRepository.Cohort.CONTROL)).isEqualTo(OnboardingRepository.Cohort.CONTROL);
        verify(events).record(account, "onboarding_v1", ExperimentEvents.Event.EXPOSURE);
    }

    @Test
    void theTourVariantAndAnyOtherVariantGetTheTour() {
        assertThat(offeredCohort(Optional.of("tour"), OnboardingRepository.Cohort.TOUR)).isEqualTo(OnboardingRepository.Cohort.TOUR);
        verify(events).record(account, "onboarding_v1", ExperimentEvents.Event.EXPOSURE);
    }

    @Test
    void aDisabledExperimentOrAMissingSecretHoldsNobodyOutCountsNothingAndStaysOutOfTheGroups() {
        assertThat(offeredCohort(Optional.empty(), OnboardingRepository.Cohort.UNASSIGNED)).isEqualTo(OnboardingRepository.Cohort.UNASSIGNED);
        verify(events, never()).record(any(), any(), any());
    }

    @Test
    void aRateLimitedExposureNeverFailsTheRead() {
        when(events.record(account, "onboarding_v1", ExperimentEvents.Event.EXPOSURE)).thenThrow(new RateLimitedException(5));
        assertThat(offeredCohort(Optional.of("tour"), OnboardingRepository.Cohort.TOUR)).isEqualTo(OnboardingRepository.Cohort.TOUR);
    }

    @Test
    void theExposureIsCountedOnlyAfterTheOfferCommits() {
        when(assignments.assigned(account, "onboarding_v1")).thenReturn(Optional.of("tour"));
        when(repository.find(account)).thenReturn(Optional.empty()).thenReturn(Optional.of(created(OnboardingRepository.Cohort.TOUR)));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.read(account, true);
            verify(events, never()).record(any(), any(), any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCommit();
            verify(events).record(account, "onboarding_v1", ExperimentEvents.Event.EXPOSURE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRolledBackOfferCountsNoExposure() {
        when(assignments.assigned(account, "onboarding_v1")).thenReturn(Optional.of("tour"));
        when(repository.find(account)).thenReturn(Optional.empty()).thenReturn(Optional.of(created(OnboardingRepository.Cohort.TOUR)));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.read(account, true);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(events, never()).record(any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aReadThatLostTheRaceCountsNoExposure() {
        when(repository.insert(eq(account), any(), eq(NOW))).thenReturn(false);
        when(assignments.assigned(account, "onboarding_v1")).thenReturn(Optional.of("tour"));
        when(repository.find(account)).thenReturn(Optional.empty()).thenReturn(Optional.of(created(OnboardingRepository.Cohort.TOUR)));

        assertThat(service.read(account, true).path("journey").path("state").stringValue()).isEqualTo("OFFERED");
        verify(events, never()).record(any(), any(), any());
    }

    @Test
    void anAccountThatIsNotNewOrIsBeforeTheReleaseIsNeverOffered() {
        when(repository.find(account)).thenReturn(Optional.empty());

        assertThat(service.read(account, false).path("journey").path("state").stringValue()).isEqualTo("NOT_OFFERED");
        when(repository.now()).thenReturn(Instant.parse("2026-10-15T23:59:59Z"));
        assertThat(service.read(account, true).path("journey").path("state").stringValue()).isEqualTo("NOT_OFFERED");
        verify(repository, never()).insert(any(), any(), any());
    }
}
