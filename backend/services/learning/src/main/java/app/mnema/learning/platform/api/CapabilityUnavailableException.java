package app.mnema.learning.platform.api;

import java.util.Objects;

/** A required learning capability (AI assessment, speech to text) is disabled or has no provider. */
public class CapabilityUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient ProblemExtension extension;

    public CapabilityUnavailableException() { this(ProblemExtension.none()); }

    /** @param extension the contract's {@code capability} and {@code reason} members */
    public CapabilityUnavailableException(ProblemExtension extension) {
        super("Learning capability unavailable", null, false, false);
        this.extension = Objects.requireNonNull(extension, "extension");
    }

    public ProblemExtension extension() { return extension; }
}
