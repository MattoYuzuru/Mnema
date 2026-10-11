package app.mnema.learning.library;

import app.mnema.learning.platform.api.ProblemExtension;

/**
 * The instance is already serving as many public reads as its bulkhead allows ({@code 503 PUBLIC_READ_BUSY}, {@code Retry-After: 1}): the read
 * is refused before it asks for a connection, so a flood of public reads never queues for the pool. That bounds, but does not remove, the competition with the
 * private API: the pool is shared, which is why {@link PublicRouteConnectionBudget} checks at start that the bulkhead and the job executor leave connections free.
 */
public final class PublicReadBusyException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;
    static final long RETRY_AFTER_SECONDS = 1;

    public PublicReadBusyException() {
        super("Public reads are busy", null, false, false);
    }

    public long retryAfterSeconds() { return RETRY_AFTER_SECONDS; }

    @Override
    public ProblemExtension extension() {
        return ProblemExtension.builder().put("retryAfter", RETRY_AFTER_SECONDS).build();
    }
}
