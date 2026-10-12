package app.mnema.learning.update.auto;

import app.mnema.learning.platform.jobs.Slice;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Updates/6 part B (#445): the pace of the fan-out, its settings and the pause a slice can ask for, without a database. */
class AutoUpdateUnitsTest {
    /** A clock the test moves. */
    private static final class Moving extends Clock {
        private Instant now = Instant.parse("2026-10-12T09:00:00Z");

        void advance(Duration by) { now = now.plus(by); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }

    @Test
    void theThrottleGivesAtMostThePerMinuteAllowanceInAnyRollingMinute() {
        Moving clock = new Moving();
        AutoThrottle throttle = new AutoThrottle(() -> 5, clock);
        assertThat(throttle.acquire(3)).isEqualTo(3);
        assertThat(throttle.retryIn()).as("some of the minute is free").isEqualTo(Duration.ZERO);
        clock.advance(Duration.ofSeconds(30));
        assertThat(throttle.acquire(3)).as("only what is left").isEqualTo(2);
        assertThat(throttle.acquire(1)).isZero();
        // the first grant leaves the window 60 s after it was made, i.e. in 30 s
        assertThat(throttle.retryIn()).isEqualTo(Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(29));
        assertThat(throttle.acquire(1)).isZero();
        assertThat(throttle.retryIn()).as("never less than a second").isEqualTo(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        // sliding, not fixed windows: only the first three left the window, the later two still count
        assertThat(throttle.acquire(5)).isEqualTo(3);
        assertThat(throttle.acquire(1)).isZero();
        // everything has left the window a minute after the last grant
        clock.advance(Duration.ofSeconds(60));
        assertThat(throttle.acquire(9)).isEqualTo(5);
    }

    @Test
    void whatASliceDidNotNeedIsGivenBackNewestFirst() {
        Moving clock = new Moving();
        AutoThrottle throttle = new AutoThrottle(() -> 10, clock);
        assertThat(throttle.acquire(4)).isEqualTo(4);
        clock.advance(Duration.ofSeconds(40));
        assertThat(throttle.acquire(4)).isEqualTo(4);
        throttle.giveBack(3);
        assertThat(throttle.acquire(10)).as("10 - 4 - 1").isEqualTo(5);
        throttle.giveBack(100);
        assertThat(throttle.acquire(100)).as("giving back more than was taken empties the window, nothing more").isEqualTo(10);
    }

    @Test
    void theSettingsDefaultToOffAndRefuseWhatIsOutOfRange() {
        AutoUpdateSettings defaults = new AutoUpdateSettings(false, 50, Duration.ofDays(30), 25, 200);
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.perMinute()).isEqualTo(50);
        assertThat(defaults.activeWindow()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.sliceSize()).isEqualTo(25);
        assertThat(defaults.scanSize()).isEqualTo(200);
        assertThat(new AutoUpdateSettings(true, 10_000, Duration.ofDays(366), 500, 2_000).enabled()).isTrue();
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 0, Duration.ofDays(30), 25, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 10_001, Duration.ofDays(30), 25, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 50, Duration.ofHours(23), 25, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 50, Duration.ofDays(367), 25, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 50, null, 25, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 50, Duration.ofDays(30), 0, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 50, Duration.ofDays(30), 501, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 50, Duration.ofDays(30), 25, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoUpdateSettings(true, 50, Duration.ofDays(30), 25, 2_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theJobPayloadNamesTheCopyAndTheRevisionAndNothingElse() {
        UUID deck = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        assertThat(AutoUpdateFanout.payload(deck, revision)).isEqualTo(JsonMapper.builder().build().createObjectNode().put("deckId", deck.toString())
                .put("revisionId", revision.toString()));
        assertThat(AutoUpdateFanout.FANOUT_QUEUE).isEqualTo("deck.update.fanout");
        assertThat(AutoUpdateFanout.AUTO_QUEUE).isEqualTo("deck.update.auto");
    }

    @Test
    void aSliceMayAskForAPauseOfAtMostAnHourAndNeverANegativeOne() {
        var cursor = JsonMapper.builder().build().createObjectNode().put("after", "x");
        assertThat(Slice.proceed(cursor, 3)).isEqualTo(new Slice.Continue(cursor, 3, Duration.ZERO));
        assertThat(Slice.proceedAfter(cursor, 3, Duration.ofSeconds(7))).isEqualTo(new Slice.Continue(cursor, 3, Duration.ofSeconds(7)));
        assertThatThrownBy(() -> Slice.proceedAfter(cursor, 3, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Slice.proceedAfter(cursor, 3, Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Slice.proceedAfter(cursor, 3, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
