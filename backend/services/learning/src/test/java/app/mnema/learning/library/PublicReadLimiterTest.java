package app.mnema.learning.library;

import app.mnema.learning.platform.api.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicReadLimiterTest {
    private static final long SECOND = 1_000_000_000L;

    /** A hand-driven monotonic clock; its zero is arbitrary, as {@link System#nanoTime}'s. */
    private final AtomicLong clock = new AtomicLong(-7 * SECOND);

    private PublicReadLimiter limiter(int guest, int coarse, int overflow, int account, int tracked) {
        return new PublicReadLimiter(new PublicRouteSettings(true, guest, coarse, overflow, account, tracked, 4), clock::get);
    }

    private static long retryAfter(Runnable action) {
        try {
            action.run();
        } catch (RateLimitedException failure) {
            return failure.retryAfterSeconds();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void aGuestIsLimitedPerNetworkAndRetryAfterIsTheRealRemainingWindow() {
        PublicReadLimiter limiter = limiter(3, 1_000, 1_000, 10, 100);
        Viewer guest = Viewer.guest("203.0.113.7");
        for (int index = 0; index < 3; index++) limiter.admit(guest);
        clock.addAndGet(20 * SECOND);
        assertThat(retryAfter(() -> limiter.admit(guest))).isEqualTo(40);
        clock.addAndGet(39 * SECOND + 1);
        assertThat(retryAfter(() -> limiter.admit(guest))).isEqualTo(1);
        limiter.admit(Viewer.guest("203.0.113.8"));
        clock.addAndGet(SECOND);
        limiter.admit(guest);
    }

    @Test
    void aRefusedRequestChargesNothing() {
        PublicReadLimiter limiter = limiter(2, 3, 100, 10, 100);
        Viewer a = Viewer.guest("2001:db8:1:1::/64", "2001:db8:1::/48");
        Viewer b = Viewer.guest("2001:db8:1:2::/64", "2001:db8:1::/48");
        limiter.admit(a);
        limiter.admit(a);
        limiter.admit(b);
        // the /48 budget (3) is spent: this request is refused and must not consume b's own /64 budget
        assertThatThrownBy(() -> limiter.admit(b)).isInstanceOf(RateLimitedException.class);
        assertThatThrownBy(() -> limiter.admit(b)).isInstanceOf(RateLimitedException.class);
        // the same /64 seen from another /48 still has exactly one request left
        Viewer elsewhere = Viewer.guest("2001:db8:1:2::/64", "2001:db8:2::/48");
        limiter.admit(elsewhere);
        assertThatThrownBy(() -> limiter.admit(elsewhere)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void rotatingSlash64sInsideOneSlash48IsBoundedByTheCoarseBudget() {
        PublicReadLimiter limiter = limiter(5, 20, 1_000, 10, 1_000);
        int admitted = 0;
        for (int network = 0; network < 500; network++) {
            try {
                limiter.admit(Viewer.guest("2001:db8:7:" + Integer.toHexString(network) + "::/64", "2001:db8:7::/48"));
                admitted++;
            } catch (RateLimitedException refused) {
                assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
            }
        }
        assertThat(admitted).isEqualTo(20);
        // another /48 is not affected
        limiter.admit(Viewer.guest("2001:db8:8:1::/64", "2001:db8:8::/48"));
        // an IPv4 guest has no coarse network
        limiter.admit(Viewer.guest("198.51.100.4"));
    }

    @Test
    void aFullGuestTableNeverStarvesAnAccountAndFoldsNewGuestsIntoOneBoundedBucket() {
        PublicReadLimiter limiter = limiter(5, 1_000, 3, 4, 100);
        for (int index = 0; index < 100; index++) limiter.admit(Viewer.guest("n" + index));
        assertThat(limiter.trackedGuests()).isEqualTo(100);

        // the account table is separate: every account is admitted while the guest table is full
        for (int index = 0; index < 50; index++) limiter.admit(Viewer.account(UUID.randomUUID(), "198.51.100.1"));
        UUID busy = UUID.randomUUID();
        for (int index = 0; index < 4; index++) limiter.admit(Viewer.account(busy, null));
        assertThatThrownBy(() -> limiter.admit(Viewer.account(busy, null))).isInstanceOf(RateLimitedException.class);

        // new guests are not all refused: they share the overflow bucket (3 a minute), the table does not grow
        limiter.admit(Viewer.guest("new-1"));
        limiter.admit(Viewer.guest("new-2"));
        limiter.admit(Viewer.guest("new-3"));
        assertThat(retryAfter(() -> limiter.admit(Viewer.guest("new-4")))).isBetween(1L, 60L);
        assertThat(limiter.trackedGuests()).isEqualTo(100);
        // guests already in the table keep their own budget
        limiter.admit(Viewer.guest("n0"));

        // when the windows expire the table is swept and new guests get their own keys again
        clock.addAndGet(61 * SECOND);
        limiter.admit(Viewer.guest("new-4"));
        assertThat(limiter.trackedGuests()).isLessThanOrEqualTo(100);
    }

    @Test
    void aFullAccountTableRefusesOnlyNewAccountsAndNeverGuests() {
        PublicReadLimiter limiter = limiter(5, 1_000, 1_000, 5, 100);
        UUID known = UUID.randomUUID();
        limiter.admit(Viewer.account(known, null));
        for (int index = 0; index < 99; index++) limiter.admit(Viewer.account(UUID.randomUUID(), null));
        assertThat(limiter.trackedAccounts()).isEqualTo(100);
        assertThat(retryAfter(() -> limiter.admit(Viewer.account(UUID.randomUUID(), null)))).isEqualTo(5);
        limiter.admit(Viewer.account(known, null));
        limiter.admit(Viewer.guest("fresh"));
        clock.addAndGet(61 * SECOND);
        limiter.admit(Viewer.account(UUID.randomUUID(), null));
    }

    @Test
    void guestsWithoutAnAddressShareOneBudget() {
        PublicReadLimiter limiter = limiter(2, 1_000, 1_000, 5, 100);
        limiter.admit(Viewer.guest(null));
        limiter.admit(Viewer.guest(null));
        assertThatThrownBy(() -> limiter.admit(Viewer.guest(null))).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void theClockIsMonotonicSoAWallClockStepCannotMatter() {
        // the limiter reads only the injected nanosecond source: a window opened at a negative origin still closes exactly a minute later
        PublicReadLimiter limiter = limiter(1, 1_000, 1_000, 5, 100);
        Viewer guest = Viewer.guest("203.0.113.9");
        limiter.admit(guest);
        clock.addAndGet(60 * SECOND - 1);
        assertThatThrownBy(() -> limiter.admit(guest)).isInstanceOf(RateLimitedException.class);
        clock.addAndGet(1);
        limiter.admit(guest);
    }

    @Test
    void settingsOutsideTheirBoundsFailAtStartup() {
        int[][] bad = {{0, 1, 1, 1, 100, 4}, {1, 0, 1, 1, 100, 4}, {1, 1, 0, 1, 100, 4}, {1, 1, 1, 0, 100, 4}, {100_001, 1, 1, 1, 100, 4},
                {1, 1, 1, 100_001, 100, 4}, {1, 1, 1, 1, 99, 4}, {1, 1, 1, 1, 1_000_001, 4}, {1, 1, 1, 1, 100, 0}, {1, 1, 1, 1, 100, 65}};
        for (int[] values : bad) {
            assertThatThrownBy(() -> new PublicRouteSettings(true, values[0], values[1], values[2], values[3], values[4], values[5]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        PublicRouteSettings defaults = new PublicRouteSettings(false, 120, 600, 600, 600, 50_000, 4);
        assertThat(defaults.enabled).isFalse();
        assertThat(defaults.maxConcurrent).isEqualTo(4);
    }
}
