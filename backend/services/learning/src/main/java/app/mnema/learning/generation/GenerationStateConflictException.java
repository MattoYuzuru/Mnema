package app.mnema.learning.generation;

import app.mnema.learning.platform.api.ProblemExtension;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A command that the current session or artifact state forbids: {@code 409 GENERATION_STATE_CONFLICT} with the
 * {@code reason} member (and {@code artifactIds} for a bulk command). 412 stays for stale versions only.
 */
public final class GenerationStateConflictException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    /** The contract's reasons ({@code contracts/generation/errors.json}). */
    public enum Reason { ILLEGAL_STATE, MEDIA_NOT_READY, SOURCE_STALE, NOT_RETRYABLE }

    private final Reason reason;
    private final transient List<UUID> artifactIds;

    public GenerationStateConflictException(Reason reason) {
        this(reason, List.of());
    }

    public GenerationStateConflictException(Reason reason, List<UUID> artifactIds) {
        super("Generation state conflict", null, false, false);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.artifactIds = List.copyOf(artifactIds);
    }

    @Override
    public ProblemExtension extension() {
        var builder = ProblemExtension.builder().put("reason", reason);
        if (!artifactIds.isEmpty()) builder.put("artifactIds", artifactIds.stream().map(UUID::toString).toList());
        return builder.build();
    }
}
