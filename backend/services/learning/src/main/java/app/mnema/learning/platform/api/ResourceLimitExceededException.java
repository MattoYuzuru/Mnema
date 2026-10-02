package app.mnema.learning.platform.api;

import java.util.Objects;

/** A session or account limit would be exceeded; the optional extension names the limit and the configured limits. */
public final class ResourceLimitExceededException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final transient ProblemExtension extension;

    public ResourceLimitExceededException() {
        this(ProblemExtension.none());
    }

    public ResourceLimitExceededException(ProblemExtension extension) {
        super("Resource limit exceeded", null, false, false);
        this.extension = Objects.requireNonNull(extension, "extension");
    }


    @Override
    public ProblemExtension extension() {
        return extension;
    }
}
