package app.mnema.learning.study.attempt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Pure, versioned mnema-baseline-v1 reducer. */
final class BaselineReducer {
    static final String ID = "mnema-baseline";
    static final String VERSION = "1";
    private static final List<Duration> INTERVALS = List.of(Duration.ofMinutes(10), Duration.ofHours(4),
            Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(14),
            Duration.ofDays(30), Duration.ofDays(60));

    Transition apply(State state, AttemptEvaluation.Result result, AttemptEvaluation.EvidenceClass evidence,
                     Instant acceptedAt) {
        Rule rule = rule(result, evidence);
        int after = switch (rule.operation()) {
            case ADD -> Math.max(state.level(), Math.min(state.level() + rule.levels(), rule.cap()));
            case SUBTRACT -> Math.max(0, state.level() - rule.levels());
            case SET -> rule.levels();
        };
        int streak = result == AttemptEvaluation.Result.CORRECT ? state.correctStreak() + 1 : 0;
        int lapses = state.lapseCount() + ((result == AttemptEvaluation.Result.INCORRECT
                || result == AttemptEvaluation.Result.UNSURE) ? 1 : 0);
        return new Transition(state.level(), after, state.correctStreak(), streak, state.lapseCount(), lapses,
                acceptedAt, acceptedAt.plus(INTERVALS.get(after)));
    }

    private static Rule rule(AttemptEvaluation.Result result, AttemptEvaluation.EvidenceClass evidence) {
        return switch (result) {
            case CORRECT -> switch (evidence) {
                case HIGH -> new Rule(Operation.ADD, 2, 7);
                case MEDIUM -> new Rule(Operation.ADD, 1, 6);
                case LOW -> new Rule(Operation.ADD, 1, 3);
            };
            case PARTIAL, UNSURE -> new Rule(Operation.SUBTRACT, 1, 7);
            case INCORRECT -> switch (evidence) {
                case HIGH -> new Rule(Operation.SET, 0, 7);
                case MEDIUM -> new Rule(Operation.SUBTRACT, 2, 7);
                case LOW -> new Rule(Operation.SUBTRACT, 1, 7);
            };
        };
    }

    record State(int level, int correctStreak, int lapseCount) { }
    record Transition(int beforeLevel, int afterLevel, int beforeCorrectStreak, int afterCorrectStreak,
                      int beforeLapseCount, int afterLapseCount, Instant acceptedAt, Instant nextDue) { }
    private record Rule(Operation operation, int levels, int cap) { }
    private enum Operation { ADD, SUBTRACT, SET }
}
