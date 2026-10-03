package app.mnema.learning.study.attempt;

/**
 * How demanding the {@code ai-semantic} grade is, a server-side function of where the learner stands on the objective
 * ({@code ai-semantic-v1}; the model never sees it, so its verdicts are the same at every level):
 * <ul>
 *   <li>{@link #S1} «Знакомство»: the objective has no assessed attempt in the current learning epoch, or its level is at most 1;</li>
 *   <li>{@link #S2} «Закрепление»: level 2 or 3;</li>
 *   <li>{@link #S3} «Владение»: level 4 or more, or a correct streak of 2 or more.</li>
 * </ul>
 * The first attempt at one exercise in an epoch is never stricter than S2, however high other exercises raised the objective.
 */
enum SemanticStrictness {
    S1, S2, S3;

    static SemanticStrictness select(boolean assessedInEpoch, int level, int correctStreak, boolean firstAttemptAtExercise) {
        SemanticStrictness base = !assessedInEpoch || level <= 1 ? S1
                : level >= 4 || correctStreak >= 2 ? S3 : S2;
        return firstAttemptAtExercise && base == S3 ? S2 : base;
    }

    /** The tier of evidence this level may give: the lenient first level only reports soft evidence, never {@code HIGH}. */
    AttemptEvaluation.EvidenceClass evidence() {
        return this == S1 ? AttemptEvaluation.EvidenceClass.LOW : AttemptEvaluation.EvidenceClass.MEDIUM;
    }

    /** Runs the grader makes: one at the lenient level, two (and their agreement) at the strict ones. */
    int runs() { return this == S1 ? 1 : 2; }

    boolean stricterThan(SemanticStrictness other) { return compareTo(other) > 0; }
}
