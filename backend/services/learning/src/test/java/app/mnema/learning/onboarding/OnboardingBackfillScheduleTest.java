package app.mnema.learning.onboarding;

import app.mnema.learning.platform.jobs.LearningJobs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The switch and the knobs of the backfill, and the enqueuer that turns it into one job per registry version. */
class OnboardingBackfillScheduleTest {
    private final LearningJobs jobs = mock(LearningJobs.class);
    private final BackfillRepository repository = mock(BackfillRepository.class);

    private static BackfillSettings settings(boolean enabled) {
        return new BackfillSettings(enabled, 1000, Duration.ofSeconds(3), Duration.ofSeconds(1));
    }

    @Test
    void offByDefaultItEnqueuesNothing() {
        new BackfillSchedule(jobs, settings(false), repository).tick();
        verify(jobs, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void onItEnqueuesOneJobPerRegistryVersionAndRemembersItAndRetriesAFailure() {
        BackfillSchedule schedule = new BackfillSchedule(jobs, settings(true), repository);
        when(jobs.enqueue(eq("onboarding.backfill"), eq("onboarding.backfill:v" + MilestoneKey.REGISTRY_VERSION), isNull(), any(ObjectNode.class)))
                .thenThrow(new IllegalStateException("database down")).thenReturn(java.util.UUID.randomUUID());
        schedule.tick();
        schedule.tick();
        schedule.tick();
        verify(jobs, times(2)).enqueue(eq("onboarding.backfill"), eq("onboarding.backfill:v1"), isNull(), any(ObjectNode.class));
        assertThat(BackfillSchedule.dedupeKey()).isEqualTo("onboarding.backfill:v1");
    }

    @Test
    void aVersionRecordedAsFinishedIsNotEnqueuedAgainAfterItsJobRowIsGone() {
        when(repository.finished(MilestoneKey.REGISTRY_VERSION)).thenReturn(true);
        BackfillSchedule schedule = new BackfillSchedule(jobs, settings(true), repository);
        schedule.tick();
        schedule.tick();
        verify(jobs, never()).enqueue(any(), any(), any(), any());
        verify(repository, times(1)).finished(MilestoneKey.REGISTRY_VERSION);
    }

    @Test
    void theKnobsAreBoundedAndTheDefaultsAreTheDocumentedOnes() {
        BackfillSettings defaults = new BackfillSettings(false, 1000, Duration.ofSeconds(3), Duration.ofSeconds(1));
        assertThat(defaults.enabled).isFalse();
        assertThat(defaults.batch).isEqualTo(1000);
        for (int bad : new int[] {-1, 0, 99, 5001}) {
            assertThatThrownBy(() -> new BackfillSettings(true, bad, Duration.ofSeconds(3), Duration.ZERO)).as("batch " + bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("batch");
        }
        assertThat(new BackfillSettings(true, 100, Duration.ofMillis(500), Duration.ZERO).batch).isEqualTo(100);
        assertThat(new BackfillSettings(true, 5000, Duration.ofSeconds(20), Duration.ofMinutes(1)).batch).isEqualTo(5000);
        for (Duration bad : new Duration[] {Duration.ofMillis(499), Duration.ofSeconds(21), Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new BackfillSettings(true, 1000, bad, Duration.ZERO)).as("budget " + bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("slice-budget");
        }
        for (Duration bad : new Duration[] {Duration.ofSeconds(-1), Duration.ofMinutes(2)}) {
            assertThatThrownBy(() -> new BackfillSettings(true, 1000, Duration.ofSeconds(3), bad)).as("pause " + bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pause");
        }
    }
}
