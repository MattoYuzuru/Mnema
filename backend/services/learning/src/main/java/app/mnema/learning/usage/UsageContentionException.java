package app.mnema.learning.usage;

/**
 * Admission lost the race for the balance row more often than any real burst of admissions can: a retryable
 * {@code 503 USAGE_UNAVAILABLE}, never a generic 500. Nothing was written; the caller's transaction rolls back.
 */
public final class UsageContentionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UsageContentionException() {
        super("Usage admission contended", null, false, false);
    }
}
