package app.mnema.learning.platform.jobs;

/** A failure of a slice that may pass on a later attempt: the slice rolls back and the job is requeued with a backoff. */
public class RetryableJobException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    /** @param code a short code of the cause ({@code [A-Za-z0-9_.-]{1,64}}), stored as {@code last_error}; never content, a message or a token */
    public RetryableJobException(String code) {
        super(code);
        this.code = JobCodes.require(code);
    }

    public String code() {
        return code;
    }
}
