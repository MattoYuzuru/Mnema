package app.mnema.learning.platform.api;

import java.util.Objects;

/**
 * Explicit input-boundary failure; deliberately retains neither input nor parser cause. A refusal that the client can explain
 * (an edit target the model cannot be given) names its {@code reason} as a problem extension member.
 */
public final class InvalidRequestException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final transient ProblemExtension extension;

    public InvalidRequestException() {
        this(ProblemExtension.none());
    }

    public InvalidRequestException(ProblemExtension extension) {
        super("Invalid request");
        this.extension = Objects.requireNonNull(extension, "extension");
    }

    /** An invalid request with a stable {@code reason} (see {@code contracts/generation/errors.json}). */
    public static InvalidRequestException because(String reason) {
        return new InvalidRequestException(ProblemExtension.builder().put("reason", reason).build());
    }

    @Override
    public ProblemExtension extension() {
        return extension;
    }
}
