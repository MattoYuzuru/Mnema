package app.mnema.learning.ai;

import java.time.Duration;

/**
 * Why a provider call produced no usable result. {@code detail} values are short stable codes such as
 * {@code http_503}; they never carry prompt or response text, keys or provider messages.
 */
public sealed interface AiFailure {
    /** The {@code outcome} label of logs, metrics and the call journal. */
    String outcome();

    /** The provider throttled the call; {@code retryAfter} is its hint (zero when absent). */
    record RateLimited(Duration retryAfter) implements AiFailure {
        public RateLimited {
            retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
        }

        @Override public String outcome() { return "RATE_LIMITED"; }
    }

    /** A 5xx, network error or truncated stream: worth retrying and falling back. */
    record Transient(String detail) implements AiFailure {
        @Override public String outcome() { return "TRANSIENT"; }
    }

    /** The deadline, the connect limit or the idle-stream limit passed. */
    record Timeout() implements AiFailure {
        @Override public String outcome() { return "TIMEOUT"; }
    }

    /** The provider answered but the text is unusable (empty, truncated JSON, malformed body). */
    record InvalidOutput(String detail) implements AiFailure {
        @Override public String outcome() { return "INVALID_OUTPUT"; }
    }

    /** The provider declined the content or rejected the request itself; never retried, never rerouted. */
    record Refusal(String detail) implements AiFailure {
        @Override public String outcome() { return "REFUSAL"; }
    }

    /** The global daily budget of the capability is spent; no provider was called. */
    record BudgetExhausted() implements AiFailure {
        @Override public String outcome() { return "BUDGET_EXHAUSTED"; }
    }

    /** No usable adapter (missing key, disabled) or the provider rejected the credentials. */
    record NotConfigured(String detail) implements AiFailure {
        @Override public String outcome() { return "NOT_CONFIGURED"; }
    }

    /** Every candidate's circuit breaker is open; no provider was called. */
    record CircuitOpen() implements AiFailure {
        @Override public String outcome() { return "CIRCUIT_OPEN"; }
    }
}
