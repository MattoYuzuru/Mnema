package app.mnema.learning.study.attempt;

/**
 * {@code 409 DISPUTE_NOT_ALLOWED}: the grade is not an AI grade, was already disputed, or is no longer the last transition of
 * its objective (a later attempt moved the objective on, so the AI evidence cannot be taken back without rewriting history).
 */
public final class DisputeNotAllowedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DisputeNotAllowedException() { super("Dispute not allowed", null, false, false); }
}
