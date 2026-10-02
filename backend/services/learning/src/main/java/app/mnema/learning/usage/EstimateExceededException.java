package app.mnema.learning.usage;

/**
 * A debit would exceed what its reservation still holds. The call it prices must not be made: the artifact fails with
 * {@code ESTIMATE_EXCEEDED} and a retry re-reserves. Nothing was written.
 */
public final class EstimateExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int held;
    private final int required;

    public EstimateExceededException(int held, int required) {
        super("Estimate exceeded", null, false, false);
        this.held = held;
        this.required = required;
    }

    /** Credits the reservation still holds. */
    public int held() {
        return held;
    }

    /** Credits the debit needs. */
    public int required() {
        return required;
    }
}
