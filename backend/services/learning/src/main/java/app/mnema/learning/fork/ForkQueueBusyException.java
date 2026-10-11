package app.mnema.learning.fork;

import app.mnema.learning.platform.api.ProblemExtension;

/**
 * The queue of the copy-materialization job is full: as many copies as {@code learning.community.forks.max-unfinished-total} are unfinished across all accounts
 * ({@code 503 FORKS_BUSY} with {@code Retry-After}). Nothing was written, the command is not recorded as done, and the same command may be sent again later. The wait is a
 * fixed hint, not a measurement: the queue drains at the pace of the job executor, which the request cannot see.
 */
public final class ForkQueueBusyException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;
    static final long RETRY_AFTER_SECONDS = 30;

    public ForkQueueBusyException() {
        super("Copies are busy", null, false, false);
    }

    public long retryAfterSeconds() { return RETRY_AFTER_SECONDS; }

    @Override
    public ProblemExtension extension() {
        return ProblemExtension.builder().put("retryAfter", RETRY_AFTER_SECONDS).build();
    }
}
