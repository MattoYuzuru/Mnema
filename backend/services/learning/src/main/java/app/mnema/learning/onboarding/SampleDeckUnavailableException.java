package app.mnema.learning.onboarding;

/** The sample deck cannot be made: the switch is off, no template is configured, or the template is missing, deleted, not marked, not published or not at LINK: {@code 409 SAMPLE_DECK_UNAVAILABLE}. */
public final class SampleDeckUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SampleDeckUnavailableException() {
        super("Sample deck unavailable", null, false, false);
    }
}
