package app.mnema.learning.platform.concurrency;

/** Raised when a mutable-resource command omits its required current row version. */
public final class VersionPreconditionRequiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public VersionPreconditionRequiredException() {
        super("A current resource version is required for this command");
    }
}
