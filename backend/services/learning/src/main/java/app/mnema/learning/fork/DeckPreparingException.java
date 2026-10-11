package app.mnema.learning.fork;

/**
 * The copy is not materialized yet ({@code 409 DECK_PREPARING}): a mutation of it or a Study start must wait until its heads are complete. Thrown only to the
 * owner of the copy, after the owner predicate, so it reveals nothing to a stranger.
 */
public final class DeckPreparingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeckPreparingException() {
        super("The deck copy is still being prepared", null, false, false);
    }
}
