package app.mnema.learning.update.apply;

/** The copy was detached from its source by its owner: it follows nothing, so there is no update to apply (409 {@code FORK_DETACHED}). Nothing was written. */
public final class ForkDetachedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ForkDetachedException() {
        super("The copy is detached from its source", null, false, false);
    }
}
