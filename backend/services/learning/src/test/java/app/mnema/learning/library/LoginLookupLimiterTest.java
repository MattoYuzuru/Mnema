package app.mnema.learning.library;

import app.mnema.learning.platform.api.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class LoginLookupLimiterTest {
    @Test
    void anOwnerGetsTenLookupsAMinuteAndTheRestOfTheWindowAsRetryAfter() {
        AtomicLong nanos = new AtomicLong(1_000);
        LoginLookupLimiter limiter = new LoginLookupLimiter(LoginLookupLimiter.DEFAULT_PER_OWNER, 1_000, nanos::get);
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < LoginLookupLimiter.DEFAULT_PER_OWNER; index++) limiter.admit(owner);
        nanos.addAndGet(10_000_000_000L);
        assertThatThrownBy(() -> limiter.admit(owner)).isInstanceOfSatisfying(RateLimitedException.class,
                refused -> assertThat(refused.retryAfterSeconds()).isEqualTo(50));
        // another owner is not affected, and the window reopens after a minute
        limiter.admit(UUID.randomUUID());
        nanos.addAndGet(50_000_000_000L);
        limiter.admit(owner);
    }

    @Test
    void theInstanceHasABudgetOfItsOwnBelowIdentitysAndARefusedLookupSpendsNothing() {
        AtomicLong nanos = new AtomicLong(0);
        LoginLookupLimiter limiter = new LoginLookupLimiter(5, 6, nanos::get);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int index = 0; index < 5; index++) limiter.admit(first);
        limiter.admit(second);
        nanos.addAndGet(20_000_000_000L);
        assertThatThrownBy(() -> limiter.admit(UUID.randomUUID())).isInstanceOfSatisfying(RateLimitedException.class,
                refused -> assertThat(refused.retryAfterSeconds()).as("the rest of the instance window").isEqualTo(40));
        assertThatThrownBy(() -> limiter.admit(second)).isInstanceOf(RateLimitedException.class);
        nanos.addAndGet(40_000_000_000L);
        // the new minute: the owner who was refused by the instance still has his five
        for (int index = 0; index < 5; index++) limiter.admit(second);
        assertThatThrownBy(() -> limiter.admit(second)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void theLimitsAreValidatedAtStart() {
        for (int[] bad : new int[][] {{0, 40}, {10, 0}, {1001, 40}, {10, 1001}}) {
            assertThatThrownBy(() -> new LoginLookupLimiter(bad[0], bad[1], System::nanoTime)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> new LoginLookupLimiter(1, 1000, System::nanoTime)).doesNotThrowAnyException();
    }
}
