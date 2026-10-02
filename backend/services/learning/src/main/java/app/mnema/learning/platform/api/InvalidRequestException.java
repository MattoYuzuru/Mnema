package app.mnema.learning.platform.api;

/** Explicit input-boundary failure; deliberately retains neither input nor parser cause. */
public final class InvalidRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidRequestException() {
        super("Invalid request");
    }
}
