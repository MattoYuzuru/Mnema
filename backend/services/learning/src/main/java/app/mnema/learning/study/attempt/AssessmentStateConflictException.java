package app.mnema.learning.study.attempt;

/** {@code 409 ASSESSMENT_STATE_CONFLICT}: the command does not fit the state of the assessment (a self-rating while the model still grades). */
public final class AssessmentStateConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AssessmentStateConflictException() { super("Assessment state conflict", null, false, false); }
}
