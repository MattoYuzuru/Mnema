package app.mnema.learning.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code learning.onboarding.rhythm.enabled} (OB-401, default false): the switch of the weekly rhythm. On, a completed SCHEDULED or PRACTICE session may meet the week
 * ({@link WeeklyRhythm}), {@code PUT /api/onboarding/preferences} checks the current week when it changes the target, the view carries {@code rhythm} (while
 * {@code learning.onboarding.enabled} is on too) and the milestone {@code RHYTHM_WEEK} is part of the path. Off, nothing is written and the milestone is hidden.
 */
@Component
final class RhythmSettings {
    private final boolean enabled;

    RhythmSettings(@Value("${learning.onboarding.rhythm.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }
}
