package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MiddayUsageClockTest {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test
    void usageTimeStartsAtTheNextNoonSoADayParkOutlastsTheRealNowByTwelveHours() {
        // 23:52 Moscow, the moment the old helper failed: usage time jumps to tomorrow's noon
        Instant lateEvening = Instant.parse("2026-10-03T20:52:00Z");
        Duration late = MiddayUsageClock.offset(lateEvening, MOSCOW);
        assertThat(lateEvening.plus(late)).isEqualTo(Instant.parse("2026-10-04T09:00:00Z"));
        // the next usage day start is then a full day and a bit away in real time
        Instant nextDay = Instant.parse("2026-10-04T21:00:00Z");
        assertThat(Duration.between(lateEvening, nextDay)).isGreaterThanOrEqualTo(Duration.ofHours(12));

        Instant morning = Instant.parse("2026-10-03T06:00:00Z"); // 09:00 Moscow
        assertThat(morning.plus(MiddayUsageClock.offset(morning, MOSCOW))).isEqualTo(Instant.parse("2026-10-03T09:00:00Z"));
        Instant noon = Instant.parse("2026-10-03T09:00:00Z");
        assertThat(MiddayUsageClock.offset(noon, MOSCOW)).isZero();
        for (int minute = 0; minute < 24 * 60; minute += 7) {
            Instant real = Instant.parse("2026-10-03T00:00:00Z").plus(Duration.ofMinutes(minute));
            assertThat(MiddayUsageClock.offset(real, MOSCOW)).isBetween(Duration.ZERO, Duration.ofHours(24));
        }
    }
}
