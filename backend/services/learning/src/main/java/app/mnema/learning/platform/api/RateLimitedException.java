package app.mnema.learning.platform.api;

/**
 * A free call that the account made too often ({@code 429 RATE_LIMITED}): the intent of «Попросить Мнему…» is limited per hour. The
 * answer carries {@code Retry-After} (whole seconds, at least one) and the same number as the problem member {@code retryAfter}.
 */
public final class RateLimitedException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Rate limited", null, false, false);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public ProblemExtension extension() {
        return ProblemExtension.builder().put("retryAfter", retryAfterSeconds).build();
    }
}
