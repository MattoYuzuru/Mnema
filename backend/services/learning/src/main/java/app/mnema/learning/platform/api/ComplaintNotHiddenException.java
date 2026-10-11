package app.mnema.learning.platform.api;

/** {@code :unhide} asked of a complaint that was not decided HIDDEN, or of a deck that is not hidden by moderation now (409). */
public final class ComplaintNotHiddenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ComplaintNotHiddenException() {
        super("Complaint does not hide the deck");
    }
}
