package app.mnema.learning.onboarding;

import java.util.EnumMap;
import java.util.Map;

/**
 * The transition table of {@code contracts/onboarding/journey.json} ({@code transitions}): per action the states it may start from and end in, whether
 * {@code stepKey} and {@code branch} are required, optional or forbidden in the command, and what it does to {@code finishedAt}. A pair that is not in the
 * table is {@code 409 ONBOARDING_TRANSITION}. A test compares this table with the fixture.
 */
enum JourneyAction {
    START(Map.of(JourneyState.OFFERED, JourneyState.IN_PROGRESS), Presence.REQUIRED, Presence.FORBIDDEN, Finish.KEEP),
    STEP(Map.of(JourneyState.IN_PROGRESS, JourneyState.IN_PROGRESS), Presence.REQUIRED, Presence.OPTIONAL, Finish.KEEP),
    PAUSE(Map.of(JourneyState.IN_PROGRESS, JourneyState.PAUSED), Presence.OPTIONAL, Presence.FORBIDDEN, Finish.KEEP),
    RESUME(Map.of(JourneyState.PAUSED, JourneyState.IN_PROGRESS), Presence.OPTIONAL, Presence.OPTIONAL, Finish.KEEP),
    SKIP(Map.of(JourneyState.OFFERED, JourneyState.SKIPPED), Presence.FORBIDDEN, Presence.FORBIDDEN, Finish.SET),
    COMPLETE(Map.of(JourneyState.IN_PROGRESS, JourneyState.COMPLETED, JourneyState.PAUSED, JourneyState.COMPLETED),
            Presence.FORBIDDEN, Presence.FORBIDDEN, Finish.SET),
    UNDO_FINISH(Map.of(JourneyState.SKIPPED, JourneyState.OFFERED, JourneyState.COMPLETED, JourneyState.PAUSED),
            Presence.FORBIDDEN, Presence.FORBIDDEN, Finish.CLEAR),
    /** Also clears the branch, counts one replay and moves the row to the current tour version; from {@code NOT_OFFERED} it creates the row outside the cohort. */
    REPLAY(Map.of(JourneyState.NOT_OFFERED, JourneyState.IN_PROGRESS, JourneyState.OFFERED, JourneyState.IN_PROGRESS,
            JourneyState.PAUSED, JourneyState.IN_PROGRESS, JourneyState.SKIPPED, JourneyState.IN_PROGRESS,
            JourneyState.COMPLETED, JourneyState.IN_PROGRESS), Presence.REQUIRED, Presence.FORBIDDEN, Finish.CLEAR);

    enum Presence { REQUIRED, OPTIONAL, FORBIDDEN }

    enum Finish { KEEP, SET, CLEAR }

    private final Map<JourneyState, JourneyState> transitions;
    private final Presence stepKey;
    private final Presence branch;
    private final Finish finish;

    JourneyAction(Map<JourneyState, JourneyState> transitions, Presence stepKey, Presence branch, Finish finish) {
        this.transitions = new EnumMap<>(transitions);
        this.stepKey = stepKey;
        this.branch = branch;
        this.finish = finish;
    }

    /** @return the state after the action, or null when the table does not allow it from {@code from} */
    JourneyState target(JourneyState from) {
        return transitions.get(from);
    }

    Map<JourneyState, JourneyState> transitions() { return Map.copyOf(transitions); }

    Presence stepKey() { return stepKey; }

    Presence branch() { return branch; }

    Finish finish() { return finish; }
}
