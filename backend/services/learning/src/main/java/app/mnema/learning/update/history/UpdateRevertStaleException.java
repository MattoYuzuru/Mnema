package app.mnema.learning.update.history;

import app.mnema.learning.platform.api.ProblemExtension;

/**
 * The unit the owner asked to return is no longer exactly what the apply made (409 {@code UPDATE_REVERT_STALE}): he edited it, deleted it or returned it since, or the copy's head
 * moved while the command was built. Nothing was written. {@code unitId} names the unit when it is known; the owner reads «Что нового» again.
 */
public final class UpdateRevertStaleException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;
    private final transient ProblemExtension extension;

    public UpdateRevertStaleException() {
        this(null);
    }

    public UpdateRevertStaleException(String unitId) {
        super("The unit changed after the update", null, false, false);
        this.extension = unitId == null ? ProblemExtension.none() : ProblemExtension.builder().put("unitId", unitId).build();
    }

    @Override
    public ProblemExtension extension() { return extension; }
}
