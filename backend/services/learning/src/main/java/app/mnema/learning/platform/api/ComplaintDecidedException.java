package app.mnema.learning.platform.api;

/** The complaint already has another decision (409): a complaint has exactly one. */
public final class ComplaintDecidedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ComplaintDecidedException() {
        super("Complaint decided");
    }
}
