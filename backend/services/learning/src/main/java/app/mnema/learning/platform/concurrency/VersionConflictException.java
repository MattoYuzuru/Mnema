package app.mnema.learning.platform.concurrency;

/** Raised when a compare-and-set command targets a stale aggregate row version. */
public final class VersionConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public VersionConflictException() {
        super("The resource changed after the supplied version was read");
    }
}
