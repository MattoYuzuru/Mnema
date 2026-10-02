package app.mnema.learning.ai;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** One breaker per (provider, capability), created on first use. */
final class BreakerRegistry {
    private final Clock clock;
    private final AiProperties.Breaker policy;
    private final ConcurrentMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    BreakerRegistry(Clock clock, AiProperties.Breaker policy) {
        this.clock = clock;
        this.policy = policy;
    }

    CircuitBreaker of(String provider, AiCapability capability) {
        return breakers.computeIfAbsent(provider + "/" + capability.label(), key -> new CircuitBreaker(clock, policy));
    }
}
