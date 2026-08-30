package app.mnema.learning.platform.concurrency;

/** Raised when a mutable-resource command omits its required current row version. */
public final class VersionPreconditionRequiredException extends RuntimeException {

    public VersionPreconditionRequiredException() {
        super("A current resource version is required for this command");
    }
}
