package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MonotonicClockTest {
    @Test
    void itNeverGoesBackwardsStartsAtTheWallClockAndIsUtc() throws InterruptedException {
        Instant before = Instant.now();
        MonotonicClock clock = new MonotonicClock();
        Instant first = clock.instant();
        Thread.sleep(5);
        Instant second = clock.instant();
        assertThat(first).isAfterOrEqualTo(before.minusMillis(5)).isBeforeOrEqualTo(second);
        assertThat(Duration.between(first, second)).isGreaterThanOrEqualTo(Duration.ofMillis(4));
        assertThat(clock.getZone()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(clock.withZone(ZoneId.of("Europe/Moscow"))).isSameAs(clock);
    }
}
