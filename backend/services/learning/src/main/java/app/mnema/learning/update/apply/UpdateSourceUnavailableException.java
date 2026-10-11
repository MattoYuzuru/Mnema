package app.mnema.learning.update.apply;

/**
 * The source of the copy cannot be read by its owner any more (deleted, made private, hidden by moderation, or its public code was rotated away): nothing can be applied
 * (409 {@code UPDATE_SOURCE_UNAVAILABLE}). Nothing was written. Carries no data, and says nothing else about the source.
 */
public final class UpdateSourceUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UpdateSourceUnavailableException() {
        super("The source of the copy is unavailable", null, false, false);
    }
}
