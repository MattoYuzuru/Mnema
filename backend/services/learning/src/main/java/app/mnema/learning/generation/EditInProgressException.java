package app.mnema.learning.generation;

import app.mnema.learning.platform.api.ProblemExtension;

import java.util.Objects;
import java.util.UUID;

/**
 * A second edit while a turn of the artifact is QUEUED or RUNNING: {@code 409 EDIT_IN_PROGRESS} naming the running turn in
 * {@code turnId} (contracts/generation/errors.json). One edit per artifact at a time.
 */
public final class EditInProgressException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final transient UUID turnId;

    public EditInProgressException(UUID turnId) {
        super("Edit in progress", null, false, false);
        this.turnId = Objects.requireNonNull(turnId, "turnId");
    }

    @Override
    public ProblemExtension extension() {
        return ProblemExtension.builder().put("turnId", turnId.toString()).build();
    }
}
