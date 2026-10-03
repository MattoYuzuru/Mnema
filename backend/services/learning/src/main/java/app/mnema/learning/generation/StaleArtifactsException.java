package app.mnema.learning.generation;

import app.mnema.learning.platform.api.ProblemExtension;

import java.util.List;
import java.util.UUID;

/**
 * {@code 412 VERSION_CONFLICT} of a generation command whose expected artifact version or revision is stale. A bulk
 * approval names the stale artifacts in {@code artifactIds} (errors.json); a single command names none.
 */
public final class StaleArtifactsException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final transient List<UUID> artifactIds;

    public StaleArtifactsException(List<UUID> artifactIds) {
        super("Artifact changed", null, false, false);
        this.artifactIds = List.copyOf(artifactIds);
    }

    @Override
    public ProblemExtension extension() {
        if (artifactIds.isEmpty()) return ProblemExtension.none();
        return ProblemExtension.builder().put("artifactIds", artifactIds.stream().map(UUID::toString).toList()).build();
    }
}
