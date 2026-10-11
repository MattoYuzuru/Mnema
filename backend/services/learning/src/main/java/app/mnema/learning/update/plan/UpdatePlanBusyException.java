package app.mnema.learning.update.plan;

import app.mnema.learning.platform.api.ProblemExtension;

/**
 * The instance is already computing as many update plans as its bulkhead allows ({@code 503 UPDATE_PLAN_BUSY}, {@code Retry-After: 1}): refused before any transaction, nothing computed.
 */
public final class UpdatePlanBusyException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;
    static final long RETRY_AFTER_SECONDS = 1;

    public UpdatePlanBusyException() {
        super("Update plans are busy", null, false, false);
    }

    public long retryAfterSeconds() { return RETRY_AFTER_SECONDS; }

    @Override
    public ProblemExtension extension() {
        return ProblemExtension.builder().put("retryAfter", RETRY_AFTER_SECONDS).build();
    }
}
