package app.mnema.learning.platform.jobs;

/**
 * The same {@code (queue, dedupeKey)} was enqueued again for another account or with another payload: the key names one piece of work, so this is a
 * defect of the caller (like an idempotency key reused with a different body), never silently answered with the first job.
 */
public final class JobIdentityConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public JobIdentityConflictException(String queue) {
        super("A job with this dedupe key already exists on queue " + queue + " with another account or payload");
    }
}
