package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Calendar boundaries in Europe/Moscow (a fixed +03:00 offset): periods, weeks, days and the Free unlock schedule. */
class UsageCalendarTest {
    private final UsageCalendar calendar = new UsageCalendar(policy("Europe/Moscow"));

    private static UsagePolicy policy(String zone) {
        return new UsagePolicy("rc-v1", zone, new BigDecimal("0.35"), "13,13,12,12", 80, Duration.ofHours(2));
    }

    @Test
    void aPeriodIsACalendarMonthInTheZone() {
        UsageCalendar.Period october = calendar.period(Instant.parse("2026-10-02T09:00:42Z"));
        assertThat(october.id()).isEqualTo("2026-10");
        assertThat(october.start()).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
        assertThat(october.end()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        // 22:00 UTC on the 31st is already November in Moscow.
        assertThat(calendar.period(Instant.parse("2026-10-31T22:00:00Z")).id()).isEqualTo("2026-11");
        assertThat(calendar.period(Instant.parse("2026-10-31T20:59:59Z")).id()).isEqualTo("2026-10");
        assertThat(calendar.period("2026-10")).isEqualTo(october);
        assertThat(calendar.period(Instant.parse("2026-12-31T21:00:00Z")).id()).isEqualTo("2027-01");
    }

    @Test
    void daysAndWeeksStartAtMidnightAndMondayMidnightInTheZone() {
        Instant friday = Instant.parse("2026-10-02T09:00:42Z");
        assertThat(calendar.dayStart(friday)).isEqualTo(Instant.parse("2026-10-01T21:00:00Z"));
        assertThat(calendar.nextDayStart(friday)).isEqualTo(Instant.parse("2026-10-02T21:00:00Z"));
        assertThat(calendar.weekStart(friday)).isEqualTo(Instant.parse("2026-09-27T21:00:00Z"));
        assertThat(calendar.nextWeekStart(friday)).isEqualTo(Instant.parse("2026-10-04T21:00:00Z"));
        // On a Monday 00:00 the week starts now and ends in seven days.
        Instant monday = Instant.parse("2026-10-04T21:00:00Z");
        assertThat(calendar.weekStart(monday)).isEqualTo(monday);
        assertThat(calendar.nextWeekStart(monday)).isEqualTo(Instant.parse("2026-10-11T21:00:00Z"));
        assertThat(calendar.windowStart(Window.MONTH, friday)).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
        assertThat(calendar.windowEnd(Window.DAY, friday)).isEqualTo(Instant.parse("2026-10-02T21:00:00Z"));
        assertThat(calendar.windowEnd(Window.WEEK, friday)).isEqualTo(Instant.parse("2026-10-04T21:00:00Z"));
        assertThat(calendar.windowEnd(Window.MONTH, friday)).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
    }

    @Test
    void freePortionsOpenOnTheFirstThenOnEachFollowingMonday() {
        // October 2026 starts on a Thursday and has four Mondays (5, 12, 19, 26): the last one unlocks nothing.
        assertThat(unlocks("2026-10-15T00:00:00Z", 4)).containsExactly("2026-09-30T21:00:00Z", "2026-10-04T21:00:00Z",
                "2026-10-11T21:00:00Z", "2026-10-18T21:00:00Z");
        // March 2027 starts on a Monday and has five: the first unlocks on the 1st, then the 8th, 15th and 22nd.
        assertThat(unlocks("2027-03-15T00:00:00Z", 4)).containsExactly("2027-02-28T21:00:00Z", "2027-03-07T21:00:00Z",
                "2027-03-14T21:00:00Z", "2027-03-21T21:00:00Z");
        // February 2027 also starts on a Monday: the 1st, then the 8th, 15th and 22nd.
        assertThat(unlocks("2027-02-15T00:00:00Z", 4)).containsExactly("2027-01-31T21:00:00Z", "2027-02-07T21:00:00Z",
                "2027-02-14T21:00:00Z", "2027-02-21T21:00:00Z");
        // A longer schedule than the month has Mondays for stops at the month's end.
        assertThat(unlocks("2026-10-15T00:00:00Z", 9)).hasSize(5);
    }

    private List<String> unlocks(String within, int portions) {
        return calendar.unlocks(calendar.period(Instant.parse(within)), portions).stream().map(Instant::toString).toList();
    }

    @Test
    void policyRejectsNonsense() {
        assertThatThrownBy(() -> policy("Mars/Olympus")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("v1", "UTC", BigDecimal.ONE, "1", 80, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", BigDecimal.ZERO, "1", 80, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", new BigDecimal("1.5"), "1", 80, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", BigDecimal.ONE, "13,x", 80, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", BigDecimal.ONE, "0", 80, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", BigDecimal.ONE, "1,1,1,1,1,1", 80, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", BigDecimal.ONE, "1", 95, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", BigDecimal.ONE, "1", 80, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsagePolicy("rc-v1", "UTC", BigDecimal.ONE, "1", 80, Duration.ofDays(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(policy("UTC").lowThresholds()).containsExactly(80, 90, 100);
        assertThat(policy("UTC").dailyBurstFraction).isEqualByComparingTo("0.35");
    }
}
