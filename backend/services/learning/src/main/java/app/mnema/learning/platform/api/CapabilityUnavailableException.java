package app.mnema.learning.platform.api;

/** A required learning capability (AI assessment, speech to text) is disabled or has no provider. */
public class CapabilityUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CapabilityUnavailableException() { super("Learning capability unavailable", null, false, false); }
}
