package app.mnema.learning.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * {@code learning.onboarding.enabled} (default false: the API answers a disabled view and refuses commands, nothing is created) and
 * {@code learning.onboarding.eligible-from}, the release instant: only an account first seen at or after it is offered the tour. With the flag on, a missing or
 * malformed instant stops startup, because an unbounded date would offer the tour to every old account.
 */
@Component
final class OnboardingSettings {
    private final boolean enabled;
    private final Instant eligibleFrom;

    OnboardingSettings(@Value("${learning.onboarding.enabled:false}") boolean enabled,
                       @Value("${learning.onboarding.eligible-from:}") String eligibleFrom) {
        this.enabled = enabled;
        if (!enabled) {
            this.eligibleFrom = null;
            return;
        }
        try {
            this.eligibleFrom = Instant.parse(eligibleFrom.strip());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Invalid onboarding settings: eligible-from must be an ISO-8601 instant when onboarding is enabled");
        }
    }

    boolean enabled() {
        return enabled;
    }

    /** @return whether an account first seen at {@code now} may be offered the tour: the flag is on and the release instant has come */
    boolean opens(Instant now) {
        return enabled && !now.isBefore(eligibleFrom);
    }
}
