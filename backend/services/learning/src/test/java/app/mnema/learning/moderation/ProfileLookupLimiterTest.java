package app.mnema.learning.moderation;

import app.mnema.learning.platform.api.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileLookupLimiterTest {
    private static final long MINUTE = 60_000_000_000L;

    @Test
    void aReporterAndTheInstanceHaveFixedWindowsAndTheRestOfTheRefusingWindowIsReported() {
        AtomicLong clock = new AtomicLong(5 * MINUTE);
        ProfileLookupLimiter limiter = new ProfileLookupLimiter(2, 3, clock::get);
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        limiter.admit(one);
        limiter.admit(one);
        clock.addAndGet(10_000_000_000L);
        assertThatThrownBy(() -> limiter.admit(one)).isInstanceOfSatisfying(RateLimitedException.class, failure -> assertThat(failure.retryAfterSeconds()).isEqualTo(50));
        limiter.admit(two);
        // the instance cap of three is spent now; another reporter is refused with the rest of the instance window
        assertThatThrownBy(() -> limiter.admit(UUID.randomUUID())).isInstanceOfSatisfying(RateLimitedException.class, failure -> assertThat(failure.retryAfterSeconds()).isEqualTo(50));
        // a refused call spent nothing: after the window everybody starts over
        clock.addAndGet(MINUTE);
        limiter.admit(one);
        limiter.admit(one);
        limiter.admit(two);
        assertThatThrownBy(() -> limiter.admit(two)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void theLimitsAreValidated() {
        assertThatThrownBy(() -> new ProfileLookupLimiter(0, 1, System::nanoTime)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfileLookupLimiter(1, 1_001, System::nanoTime)).isInstanceOf(IllegalArgumentException.class);
    }
}
