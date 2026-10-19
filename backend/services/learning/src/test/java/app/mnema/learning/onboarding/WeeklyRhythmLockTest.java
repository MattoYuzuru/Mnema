package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** {@link WeeklyRhythm#lockForCompletion}: the early lock of a writer that publishes before it may complete a session, taken only where the rhythm counts. */
class WeeklyRhythmLockTest {
    private final UUID account = UUID.randomUUID();
    private final RhythmRepository repository = mock(RhythmRepository.class);

    private WeeklyRhythm rhythm(boolean enabled) {
        return new WeeklyRhythm(repository, new RhythmSettings(enabled), mock(MilestoneRecorder.class));
    }

    @Test
    void aScheduledOrPracticeSessionTakesTheLockWhileTheRhythmIsOn() {
        WeeklyRhythm rhythm = rhythm(true);

        rhythm.lockForCompletion(account, "SCHEDULED");
        rhythm.lockForCompletion(account, "PRACTICE");

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2)).lock(account);
    }

    @Test
    void aReplayTakesNoLockAndNeitherDoesAnythingWhileTheRhythmIsOff() {
        rhythm(true).lockForCompletion(account, "REPLAY");
        rhythm(false).lockForCompletion(account, "SCHEDULED");
        rhythm(false).lockForCompletion(account, "PRACTICE");

        verify(repository, never()).lock(any());
    }
}
