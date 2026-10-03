package app.mnema.learning.generation;

import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.generation.SessionLifecycle.Failure;

import java.time.Duration;
import java.time.Instant;

/** What the provider layer gave up with, mapped to a step-level retry with backoff or a final failure (shared by the text steps). */
final class ProviderFailures {
    private ProviderFailures() { }

    static Failure of(AiFailure failure, StepClaim claim, SessionLifecycle lifecycle) {
        return switch (failure) {
            case AiFailure.RateLimited limited -> Failure.retry("PROVIDER_UNAVAILABLE",
                    max(limited.retryAfter(), lifecycle.backoff(claim.attempt())));
            case AiFailure.Transient ignored -> Failure.retry("PROVIDER_UNAVAILABLE", lifecycle.backoff(claim.attempt()));
            case AiFailure.CircuitOpen ignored -> Failure.retry("PROVIDER_UNAVAILABLE", lifecycle.backoff(claim.attempt()));
            case AiFailure.Timeout ignored -> Instant.now().isBefore(claim.deadlineAt())
                    ? Failure.retry("PROVIDER_UNAVAILABLE", lifecycle.backoff(claim.attempt())) : Failure.fail("DEADLINE_EXCEEDED");
            case AiFailure.InvalidOutput ignored -> Failure.fail("INVALID_OUTPUT");
            case AiFailure.Refusal ignored -> Failure.fail("REFUSAL");
            case AiFailure.BudgetExhausted ignored -> Failure.fail("PROVIDER_UNAVAILABLE");
            case AiFailure.NotConfigured ignored -> Failure.fail("PROVIDER_UNAVAILABLE");
        };
    }

    private static Duration max(Duration left, Duration right) { return left.compareTo(right) >= 0 ? left : right; }
}
