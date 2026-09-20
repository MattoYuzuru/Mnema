package app.mnema.learning.study.attempt;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineReducerTest {
    private final BaselineReducer reducer = new BaselineReducer();
    private final Instant now = Instant.parse("2026-09-20T09:00:00Z");

    @Test
    void executesGoldenPromotionCapsAndFailureRules() {
        assertTransition(0, AttemptEvaluation.Result.CORRECT, AttemptEvaluation.EvidenceClass.HIGH, 2,
                "2026-09-21T09:00:00Z");
        assertTransition(3, AttemptEvaluation.Result.CORRECT, AttemptEvaluation.EvidenceClass.LOW, 3,
                "2026-09-23T09:00:00Z");
        assertTransition(7, AttemptEvaluation.Result.CORRECT, AttemptEvaluation.EvidenceClass.LOW, 7,
                "2026-11-19T09:00:00Z");
        assertTransition(7, AttemptEvaluation.Result.CORRECT, AttemptEvaluation.EvidenceClass.MEDIUM, 7,
                "2026-11-19T09:00:00Z");
        assertTransition(6, AttemptEvaluation.Result.INCORRECT, AttemptEvaluation.EvidenceClass.HIGH, 0,
                "2026-09-20T09:10:00Z");
        assertTransition(5, AttemptEvaluation.Result.INCORRECT, AttemptEvaluation.EvidenceClass.MEDIUM, 3,
                "2026-09-23T09:00:00Z");
        assertTransition(0, AttemptEvaluation.Result.UNSURE, AttemptEvaluation.EvidenceClass.LOW, 0,
                "2026-09-20T09:10:00Z");
    }

    @Test
    void correctnessControlsStreakWhileOnlyIncorrectAndUnsureAddLapses() {
        BaselineReducer.State state = new BaselineReducer.State(3, 4, 2);
        var correct = reducer.apply(state, AttemptEvaluation.Result.CORRECT,
                AttemptEvaluation.EvidenceClass.HIGH, now);
        var partial = reducer.apply(state, AttemptEvaluation.Result.PARTIAL,
                AttemptEvaluation.EvidenceClass.HIGH, now);
        var unsure = reducer.apply(state, AttemptEvaluation.Result.UNSURE,
                AttemptEvaluation.EvidenceClass.HIGH, now);

        assertThat(correct.afterCorrectStreak()).isEqualTo(5);
        assertThat(correct.afterLapseCount()).isEqualTo(2);
        assertThat(partial.afterCorrectStreak()).isZero();
        assertThat(partial.afterLapseCount()).isEqualTo(2);
        assertThat(unsure.afterLapseCount()).isEqualTo(3);
    }

    private void assertTransition(int before, AttemptEvaluation.Result result,
                                  AttemptEvaluation.EvidenceClass evidence, int after, String due) {
        var transition = reducer.apply(new BaselineReducer.State(before, 0, 0), result, evidence, now);
        assertThat(transition.afterLevel()).isEqualTo(after);
        assertThat(transition.nextDue()).isEqualTo(Instant.parse(due));
    }
}
