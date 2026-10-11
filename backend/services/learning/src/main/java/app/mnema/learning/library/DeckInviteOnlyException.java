package app.mnema.learning.library;

/** The deck exists but is shared by invitation and the viewer holds no grant ({@code 403 DECK_INVITE_ONLY}); it carries nothing about the deck. */
public final class DeckInviteOnlyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeckInviteOnlyException() {
        super("Deck is shared by invitation", null, false, false);
    }
}
