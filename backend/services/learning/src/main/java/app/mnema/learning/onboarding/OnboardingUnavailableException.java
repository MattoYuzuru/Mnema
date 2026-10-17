package app.mnema.learning.onboarding;

/** The tour is turned off ({@code learning.onboarding.enabled}): {@code 409 ONBOARDING_UNAVAILABLE}. */
public final class OnboardingUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OnboardingUnavailableException() {
        super("Onboarding unavailable", null, false, false);
    }
}
