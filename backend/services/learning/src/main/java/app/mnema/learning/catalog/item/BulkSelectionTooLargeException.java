package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.ProblemExtension;

/** A bulk selection resolves to more materials than one request may process: 422 {@code BULK_SELECTION_TOO_LARGE}. */
public final class BulkSelectionTooLargeException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    public BulkSelectionTooLargeException() { super("Bulk selection too large", null, false, false); }

    @Override public ProblemExtension extension() { return ProblemExtension.limit(BulkSelection.MAX_SELECTION); }
}
