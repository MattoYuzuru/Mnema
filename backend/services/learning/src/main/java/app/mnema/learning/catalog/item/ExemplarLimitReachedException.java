package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.ProblemExtension;

/** A Deck already has the maximum number of exemplars: 422 {@code EXEMPLAR_LIMIT_REACHED} with {@code limit}. */
public final class ExemplarLimitReachedException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    public ExemplarLimitReachedException() { super("Exemplar limit reached", null, false, false); }

    @Override public ProblemExtension extension() { return ProblemExtension.limit(ItemExemplarService.MAX_EXEMPLARS); }
}
