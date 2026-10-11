package app.mnema.learning.library;

import app.mnema.learning.platform.api.RateLimitedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicMediaLimiterTest {
    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong clock = new AtomicLong(-3 * SECOND);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private PublicMediaLimiter limiter(int guest, int coarse, int deck, int overflow, int tracked) {
        return new PublicMediaLimiter(new PublicMediaSettings(true, guest, coarse, deck, overflow, tracked), clock::get, meters);
    }

    private double events(String outcome) {
        var counter = meters.find("mnema_public_media_requests_total").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
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
    void aGuestNetworkHasItsOwnWindowAndRetryAfterIsTheRealRemainder() {
        PublicMediaLimiter limiter = limiter(2, 1000, 100, 100, 100);
        Viewer guest = Viewer.guest("203.0.113.7");
        limiter.admitNetwork(guest);
        limiter.admitNetwork(guest);
        clock.addAndGet(25 * SECOND);
        assertThat(retryAfter(() -> limiter.admitNetwork(guest))).isEqualTo(35);
        limiter.admitNetwork(Viewer.guest("203.0.113.8"));
        limiter.admitNetwork(Viewer.guest(null));
        clock.addAndGet(35 * SECOND);
        limiter.admitNetwork(guest);
    }

    @Test
    void aSlash48IsOneBudgetAcrossItsSlash64sAndARefusedRequestChargesNothing() {
        PublicMediaLimiter limiter = limiter(2, 3, 100, 100, 100);
        Viewer a = Viewer.guest("2001:db8:1:1::/64", "2001:db8:1::/48");
        Viewer b = Viewer.guest("2001:db8:1:2::/64", "2001:db8:1::/48");
        Viewer c = Viewer.guest("2001:db8:1:3::/64", "2001:db8:1::/48");
        limiter.admitNetwork(a);
        limiter.admitNetwork(a);
        limiter.admitNetwork(b);
        // the /48 is full (3): a fresh /64 of the same site is refused, and so is a request the /64 window would have allowed
        assertThat(retryAfter(() -> limiter.admitNetwork(c))).isEqualTo(60);
        // the refused request did not charge the fine window of c: after the /48 window passes c has its whole budget
        clock.addAndGet(60 * SECOND);
        limiter.admitNetwork(c);
        limiter.admitNetwork(c);
        assertThat(retryAfter(() -> limiter.admitNetwork(c))).isEqualTo(60);
        // another site and a network without a /48 (IPv4) are unaffected
        limiter.admitNetwork(Viewer.guest("2001:db8:2:1::/64", "2001:db8:2::/48"));
        limiter.admitNetwork(Viewer.guest("203.0.113.9"));
        assertThat(limiter.trackedCoarse()).isEqualTo(2);
    }

    @Test
    void aDeckWindowCountsAllGuestsOfTheDeckAndLeavesOtherDecksAlone() {
        PublicMediaLimiter limiter = limiter(100, 1000, 3, 100, 100);
        UUID deck = UUID.randomUUID();
        for (int index = 0; index < 3; index++) limiter.admitDeck(deck);
        assertThat(retryAfter(() -> limiter.admitDeck(deck))).isEqualTo(60);
        limiter.admitDeck(UUID.randomUUID());
        clock.addAndGet(60 * SECOND);
        limiter.admitDeck(deck);
    }

    @Test
    void aFullTableFoldsNewKeysIntoTheOverflowBucketInsteadOfGrowing() {
        PublicMediaLimiter limiter = limiter(100, 1000, 100, 2, 100);
        for (int index = 0; index < 100; index++) limiter.admitNetwork(Viewer.guest("198.51.100." + index));
        assertThat(limiter.tracked()).isEqualTo(100);
        // two more new guests share the overflow bucket of two, the third is refused, and nothing was added to the table
        limiter.admitNetwork(Viewer.guest("192.0.2.1"));
        limiter.admitNetwork(Viewer.guest("192.0.2.2"));
        assertThatThrownBy(() -> limiter.admitNetwork(Viewer.guest("192.0.2.3"))).isInstanceOf(RateLimitedException.class);
        assertThat(limiter.tracked()).isEqualTo(100);
        assertThat(events("limiter_network_table_full")).isEqualTo(3);
        assertThat(events("limiter_network_overflow_bucket")).isEqualTo(2);
        // expired windows are swept when the table is full, so known guests come back
        clock.addAndGet(61 * SECOND);
        limiter.admitNetwork(Viewer.guest("192.0.2.9"));
        assertThat(limiter.tracked()).isLessThan(100);
    }

    @Test
    void settingsOutsideTheirBoundsFailAtStartup() {
        assertThatThrownBy(() -> new PublicMediaSettings(true, 0, 10, 10, 10, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicMediaSettings(true, 10, 10, 0, 10, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicMediaSettings(true, 10, 10, 10, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicMediaSettings(true, 10, 0, 10, 10, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicMediaSettings(true, 10, 10, 10, 10, 99)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PublicMediaSettings(false, 10, 10, 10, 10, 100).enabled).isFalse();
    }
}
