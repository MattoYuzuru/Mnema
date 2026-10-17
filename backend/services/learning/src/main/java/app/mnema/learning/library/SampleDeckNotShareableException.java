package app.mnema.learning.library;

/** A sample deck or the sample template was published, shared or copied: {@code 409 SAMPLE_DECK_NOT_SHAREABLE}. */
public final class SampleDeckNotShareableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SampleDeckNotShareableException() {
        super("A sample deck cannot be published, shared or copied", null, false, false);
    }
}
