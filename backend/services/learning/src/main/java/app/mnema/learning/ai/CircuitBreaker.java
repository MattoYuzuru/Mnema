package app.mnema.learning.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-(provider, capability) circuit breaker: {@code threshold} consecutive failures inside {@code window} open it for
 * {@code openFor}; after that exactly one probe call is let through (half-open), and its outcome closes or reopens it.
 * "Success" means the provider answered, whatever the content; only transport and credential failures count against it.
 * All time comes from the injected {@link Clock}.
 *
 * <p>Every granted call holds a <em>ticket</em> (the generation of the breaker when it was granted) and reports with it. The
 * generation advances each time the breaker opens, so a slow call that was admitted before the breaker opened cannot close it
 * with a stale success or count a stale failure against the next streak.
 */
final class CircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Returned by {@link #tryAcquire} when the call is refused. */
    static final long REFUSED = -1;

    private final Clock clock;
    private final int threshold;
    private final Duration window;
    private final Duration openFor;
    private State state = State.CLOSED;
    private long generation;
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

    /**
     * Asks permission for one call.
     *
     * @return the ticket to report with, or {@link #REFUSED}; every granted ticket must end in {@link #onSuccess},
     *         {@link #onFailure} or {@link #release}
     */
    synchronized long tryAcquire() {
        switch (state) {
            case CLOSED:
                return generation;
            case OPEN:
                if (clock.instant().isBefore(openUntil)) return REFUSED;
                state = State.HALF_OPEN;
                probing = true;
                return generation;
            default:
                if (probing) return REFUSED;
                probing = true;
                return generation;
        }
    }

    synchronized void onSuccess(long ticket) {
        if (ticket != generation) return;
        state = State.CLOSED;
        failures = 0;
        streakStart = null;
        probing = false;
    }

    synchronized void onFailure(long ticket) {
        if (ticket != generation) return;
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
    synchronized void release(long ticket) {
        if (ticket == generation) probing = false;
    }

    /** The ticket of the current generation, for a failure that belongs to no single call (an exhausted retry ladder). */
    synchronized long currentTicket() { return generation; }

    /** True while calls are being refused; false once the open period has elapsed and a probe may go. */
    synchronized boolean isOpen() {
        return (state == State.OPEN && clock.instant().isBefore(openUntil)) || (state == State.HALF_OPEN && probing);
    }

    synchronized State state() { return state; }

    private void open(Instant now) {
        state = State.OPEN;
        generation++;
        openUntil = now.plus(openFor);
        failures = 0;
        streakStart = null;
        probing = false;
    }
}
