package app.mnema.learning.onboarding;

import java.time.LocalDate;
import java.util.List;

/**
 * The {@code rhythm} member of the onboarding view ({@code views.rhythmWeek} of {@code contracts/onboarding/journey.json}): the account's week as the server counts it.
 *
 * @param weekStart the Monday of the ISO week that contains {@code today}
 * @param today the account's local date (its time zone, as Study takes it)
 * @param target the weekly target of the journey, or the default 3
 * @param days the distinct local study dates of the completed SCHEDULED and PRACTICE sessions in {@code [weekStart, weekStart + 7)}, ascending
 * @param weeksMet the weeks in which the target was met
 */
record RhythmState(LocalDate weekStart, LocalDate today, int target, List<LocalDate> days, int weeksMet) {
    RhythmState {
        days = List.copyOf(days);
    }
}
