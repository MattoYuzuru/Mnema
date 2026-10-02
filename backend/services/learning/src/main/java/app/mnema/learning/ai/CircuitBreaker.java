package app.mnema.learning.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-(provider, capability) circuit breaker: {@code threshold} consecutive failures inside {@code window} open it for
 * {@code openFor}; after that exactly one probe call is let through (half-open), and its outcome closes or reopens it.
 * "Success" means the provider answered, whatever the content; only transport and credential failures count against it.
 * All time comes from the injected {@link Clock}.
 */
final class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    private final Clock clock;
    private final int threshold;
    private final Duration window;
    private final Duration openFor;
    private State state = State.CLOSED;
    private int failures;
    private Instant streakStart;
    private Instant openUntil;
    private boolean probing;

    CircuitBreaker(Clock clock, AiProperties.Breaker policy) {
        this.clock = clock;
        this.threshold = policy.failureThreshold();
        this.window = policy.window();
        this.openFor = policy.openFor();
    }

    /** Asks permission for one call; every granted call must report {@link #onSuccess} or {@link #onFailure}. */
    synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.instant().isBefore(openUntil)) return false;
                state = State.HALF_OPEN;
                probing = true;
                return true;
            default:
                if (probing) return false;
                probing = true;
                return true;
        }
    }

    synchronized void onSuccess() {
        state = State.CLOSED;
        failures = 0;
        streakStart = null;
        probing = false;
    }

    synchronized void onFailure() {
        Instant now = clock.instant();
        if (state == State.HALF_OPEN) {
            open(now);
            return;
        }
        if (streakStart == null || now.isAfter(streakStart.plus(window))) {
            streakStart = now;
            failures = 0;
        }
        failures++;
        if (failures >= threshold) open(now);
    }

    /** Gives back a permission that was granted but never used, so a half-open probe slot is not lost. */
    synchronized void release() { probing = false; }

    /** True while calls are being refused; false once the open period has elapsed and a probe may go. */
    synchronized boolean isOpen() {
        return (state == State.OPEN && clock.instant().isBefore(openUntil)) || (state == State.HALF_OPEN && probing);
    }

    synchronized State state() { return state; }

    private void open(Instant now) {
        state = State.OPEN;
        openUntil = now.plus(openFor);
        failures = 0;
        streakStart = null;
        probing = false;
    }
}
