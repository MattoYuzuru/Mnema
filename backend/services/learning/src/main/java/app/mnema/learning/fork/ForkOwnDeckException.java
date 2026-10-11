package app.mnema.learning.fork;

/** The viewer owns the deck he asked to copy ({@code 409 FORK_OWN_DECK}). */
public final class ForkOwnDeckException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ForkOwnDeckException() {
        super("A deck cannot be copied by its owner", null, false, false);
    }
}
