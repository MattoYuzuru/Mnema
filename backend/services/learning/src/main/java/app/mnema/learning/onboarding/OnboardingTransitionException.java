package app.mnema.learning.onboarding;

/** A tour command the transition table does not allow from the current state: {@code 409 ONBOARDING_TRANSITION}. */
public final class OnboardingTransitionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OnboardingTransitionException() {
        super("Onboarding transition not allowed", null, false, false);
    }
}
