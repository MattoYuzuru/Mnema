package app.mnema.learning.usage;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Usage time for test contexts whose steps run on the real clock (the generation worker): it runs at real speed but
 * starts at the next 12:00 of {@code learning.usage.calendar-zone}, never in the past. A daily-burst park therefore ends at
 * least twelve hours after the real now, whatever the wall clock says, so "parked" holds for the whole test and no day
 * boundary falls inside a run shorter than twelve hours. Every usage decision (periods, unlocks, bursts, expiries) uses this
 * one clock, so they stay consistent with each other.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MiddayUsageClock {
    @Bean
    @Primary
    UsageClock middayUsageClock(UsagePolicy policy) {
        Duration offset = offset(Instant.now(), policy.zone);
        return () -> Instant.now().plus(offset);
    }

    /** From {@code real} to the next 12:00 in {@code zone} (zero at exactly noon); always in {@code [0, 24h)}. */
    static Duration offset(Instant real, java.time.ZoneId zone) {
        ZonedDateTime local = real.atZone(zone);
        ZonedDateTime midday = local.with(LocalTime.NOON);
        if (midday.isBefore(local)) midday = midday.plusDays(1);
        return Duration.between(real, midday.toInstant());
    }
}
