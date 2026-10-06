package app.mnema.learning.usage;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Every day, week and month boundary of usage is a boundary of {@code learning.usage.calendar-zone}
 * (Europe/Moscow by owner decision 2026-10-02): this is the only class that turns an instant into a calendar window.
 */
@Component
public final class UsageCalendar {
    /** The allowance period: one calendar month, named {@code yyyy-MM}. */
    record Period(String id, Instant start, Instant end) { }

    private final ZoneId zone;

    UsageCalendar(UsagePolicy policy) {
        this.zone = policy.zone;
    }

    /** Calendar months preserve the local time and clamp a missing day to the target month's last day. */
    public Instant plusMonths(Instant start, long months) {
        return start.atZone(zone).plusMonths(months).toInstant();
    }

    /** The date a learner sees under the same policy that determines their allowance windows. */
    public LocalDate date(Instant at) {
        return at.atZone(zone).toLocalDate();
    }

    Period period(Instant now) {
        return of(YearMonth.from(now.atZone(zone)));
    }

    /** The period a stored {@code period_id} names. */
    Period period(String id) {
        return of(YearMonth.parse(id));
    }

    private Period of(YearMonth month) {
        return new Period(month.toString(), month.atDay(1).atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }

    Instant dayStart(Instant now) {
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    Instant nextDayStart(Instant now) {
        return now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** Monday 00:00 of the week containing {@code now}. */
    Instant weekStart(Instant now) {
        return start(now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
    }

    Instant nextWeekStart(Instant now) {
        return start(now.atZone(zone).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
    }

    Instant windowStart(Window window, Instant now) {
        return switch (window) {
            case DAY -> dayStart(now);
            case WEEK -> weekStart(now);
            case MONTH -> period(now).start();
        };
    }

    /** The instant the window instance containing {@code now} refreshes. */
    Instant windowEnd(Window window, Instant now) {
        return switch (window) {
            case DAY -> nextDayStart(now);
            case WEEK -> nextWeekStart(now);
            case MONTH -> period(now).end();
        };
    }

    /**
     * The instants at which the Free portions unlock: the start of the period, then each following Monday 00:00. The
     * list has one instant per portion; a month always has enough Mondays and a fifth one unlocks nothing extra.
     */
    List<Instant> unlocks(Period period, int portions) {
        List<Instant> unlocks = new ArrayList<>(portions);
        unlocks.add(period.start());
        LocalDate day = period.start().atZone(zone).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        while (unlocks.size() < portions) {
            Instant at = start(day);
            if (!at.isBefore(period.end())) break;
            unlocks.add(at);
            day = day.plusWeeks(1);
        }
        return unlocks;
    }

    private Instant start(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }
}
