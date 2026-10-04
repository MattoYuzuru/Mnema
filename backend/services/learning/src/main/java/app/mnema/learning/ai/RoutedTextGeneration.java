package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * The {@link TextGeneration} implementation: applies the failure policy of the architecture (§4) around the adapters.
 *
 * <ul>
 *   <li><b>429</b>: wait {@code max(Retry-After, jitter)} and retry the same candidate up to {@code rate-limit-retries}
 *       times, never past the deadline; then the next candidate.</li>
 *   <li><b>5xx, network error, per-attempt timeout</b>: up to {@code transient-attempts} tries with full-jitter backoff,
 *       then the next candidate of the route.</li>
 *   <li><b>Invalid output</b> (empty, malformed JSON): one repair on the same candidate, then the escalation route
 *       ({@code text-fast} to {@code text-strong}), then the failure.</li>
 *   <li><b>Refusal, rejected credentials, deadline</b>: no retry, no fallback.</li>
 *   <li><b>Open circuit</b>: the candidate is skipped without a call; budget spent: no call at all.</li>
 * </ul>
 *
 * <p>Time (deadline, backoff) comes from the injected clock, a {@link MonotonicClock} in production so that wall-clock
 * steps cannot shorten or extend a call.
 *
 * <p>Each provider call has an intent row before it and an outcome row after it, and the whole method refuses to run
 * inside a database transaction: a connection must never be held while a model thinks.
 */
final class RoutedTextGeneration implements TextGeneration {
    private static final Logger LOG = LoggerFactory.getLogger(RoutedTextGeneration.class);
    private final AiRouting routing;
    private final BreakerRegistry breakers;
    private final AiBudget budget;
    private final CallJournal journal;
    private final AiTelemetry telemetry;
    private final AiProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private final Map<AiCapability, Semaphore> permits = new EnumMap<>(AiCapability.class);

    RoutedTextGeneration(AiRouting routing, BreakerRegistry breakers, AiBudget budget, CallJournal journal,
                         AiTelemetry telemetry, AiProperties properties, Clock clock, Sleeper sleeper,
                         RandomGenerator random) {
        this.routing = routing;
        this.breakers = breakers;
        this.budget = budget;
        this.journal = journal;
        this.telemetry = telemetry;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
        for (AiCapability capability : AiCapability.values()) {
            permits.put(capability, new Semaphore(properties.permits().of(capability)));
        }
    }

    @Override
    public AiResult<TextResponse> generate(TextRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A provider call must not run inside a database transaction");
        }
        AiCapability capability = request.route().capability();
        List<AiRouting.Candidate> primary = routing.candidates(request.route());
        if (primary.isEmpty()) return AiResult.failed(new AiFailure.NotConfigured("no_adapter"));
        if (budget.exhausted(capability)) return AiResult.failed(new AiFailure.BudgetExhausted());
        Instant deadline = clock.instant().plus(request.deadline());
        Semaphore semaphore = permits.get(capability);
        Duration wait = min(properties.permits().queueWait(), remaining(deadline));
        try {
            if (!semaphore.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return AiResult.failed(new AiFailure.Transient("interrupted"));
        }
        try {
            return route(request, primary, deadline);
        } finally {
            semaphore.release();
        }
    }

    private AiResult<TextResponse> route(TextRequest request, List<AiRouting.Candidate> primary, Instant deadline) {
        Delivery delivery = request.listener() == null ? null : new Delivery(request.listener());
        TextRequest routed = delivery == null ? request : request.withListener(delivery);
        List<AiRouting.Candidate> sequence = primary;
        Set<String> tried = new HashSet<>();
        boolean escalated = false;
        AiFailure last = null;
        int index = 0;
        while (index < sequence.size()) {
            if (Thread.currentThread().isInterrupted()) return AiResult.failed(new AiFailure.Transient("interrupted"));
            AiRouting.Candidate candidate = sequence.get(index++);
            if (!tried.add(candidate.key())) continue;
            AiResult<TextResponse> result = runCandidate(candidate, routed, delivery, deadline);
            if (result instanceof AiResult.Ok<TextResponse>) return result;
            AiFailure failure = ((AiResult.Failed<TextResponse>) result).failure();
            last = failure;
            if (delivery != null) delivery.restart();
            if (failure instanceof AiFailure.InvalidOutput) {
                AiRoute next = request.route().escalation();
                if (escalated || next == null) return result;
                escalated = true;
                sequence = routing.candidates(next);
                index = 0;
            } else if (!fallbackable(failure, deadline)) {
                return result;
            }
        }
        return AiResult.failed(last == null ? new AiFailure.NotConfigured("no_candidate") : last);
    }

    private boolean fallbackable(AiFailure failure, Instant deadline) {
        return switch (failure) {
            case AiFailure.RateLimited ignored -> true;
            case AiFailure.Transient ignored -> true;
            case AiFailure.CircuitOpen ignored -> true;
            case AiFailure.Timeout ignored -> !remaining(deadline).isZero();
            default -> false;
        };
    }

    /** All attempts on one candidate: retries, the single repair and the circuit check. */
    private AiResult<TextResponse> runCandidate(AiRouting.Candidate candidate, TextRequest first, Delivery delivery,
                                                Instant deadline) {
        TextRequest request = first;
        boolean repaired = false;
        int transientTries = 0;
        int rateLimitTries = 0;
        while (true) {
            Duration remaining = remaining(deadline);
            if (remaining.isZero()) return AiResult.failed(new AiFailure.Timeout());
            // one attempt of a capped route (grading) may not eat the whole deadline: a slow provider hands over to the next candidate
            Duration cap = properties.routes().attemptCap(first.route());
            boolean capped = cap != null && cap.compareTo(remaining) < 0;
            AiResult<TextResponse> result = attemptOnce(candidate, request, capped ? cap : remaining);
            if (result instanceof AiResult.Ok<TextResponse>) return result;
            AiFailure failure = ((AiResult.Failed<TextResponse>) result).failure();
            if (delivery != null) delivery.restart();
            switch (failure) {
                case AiFailure.RateLimited limited -> {
                    if (++rateLimitTries > properties.retry().rateLimitRetries()) {
                        CircuitBreaker breaker = breakers.of(candidate.provider(), first.route().capability());
                        breaker.onFailure(breaker.currentTicket());
                        return result;
                    }
                    Duration pause = max(limited.retryAfter(), jitter(rateLimitTries));
                    if (pause.compareTo(properties.retry().rateLimitMaxWait()) > 0 || pause.compareTo(remaining(deadline)) >= 0) {
                        return result;
                    }
                    if (!pause(pause)) return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
                case AiFailure.Transient ignored -> {
                    if (++transientTries >= properties.retry().transientAttempts()) return result;
                    if (!pause(min(jitter(transientTries), remaining(deadline)))) {
                        return AiResult.failed(new AiFailure.Transient("interrupted"));
                    }
                }
                case AiFailure.Timeout ignored -> {
                    // The whole deadline passing is final; an idle or connect timeout inside it is retryable.
                    if (capped || remaining(deadline).isZero() || ++transientTries >= properties.retry().transientAttempts()) return result;
                }
                case AiFailure.InvalidOutput invalid -> {
                    if (repaired) return result;
                    repaired = true;
                    request = first.withRepair(invalid.detail());
                }
                default -> {
                    return result;
                }
            }
        }
    }

    /**
     * One journaled, metered, breaker-guarded provider call. The breaker ticket is settled right after the adapter returns and,
     * whatever happens afterwards (journal, metrics, an Error), a ticket that was never settled is released in {@code finally},
     * so a half-open probe slot cannot leak.
     */
    private AiResult<TextResponse> attemptOnce(AiRouting.Candidate candidate, TextRequest request, Duration remaining) {
        AiCapability capability = request.route().capability();
        CircuitBreaker breaker = breakers.of(candidate.provider(), capability);
        long ticket = breaker.tryAcquire();
        if (ticket == CircuitBreaker.REFUSED) return AiResult.failed(new AiFailure.CircuitOpen());
        boolean settled = false;
        try {
            UUID callId;
            try {
                callId = journal.begin(new CallJournal.Intent(request.stepId(), request.attempt(), capability,
                        candidate.provider(), candidate.model(), request.fingerprint()));
            } catch (RuntimeException exception) {
                warn("journal_begin", exception, candidate, request);
                return AiResult.failed(new AiFailure.Transient("journal_unavailable"));
            }
            long started = System.nanoTime();
            AiResult<TextResponse> result;
            try {
                result = candidate.adapter().attempt(candidate.model(), request, remaining);
            } catch (RuntimeException exception) {
                if (request.listener() instanceof Delivery delivery && delivery.failed()) {
                    // The caller's own listener threw: retrying would replay text into a broken consumer. Not a provider fault.
                    warn("stream_listener", exception, candidate, request);
                    result = AiResult.failed(new AiFailure.Refusal("listener_failed"));
                } else {
                    warn("adapter", exception, candidate, request);
                    result = AiResult.failed(new AiFailure.Transient("adapter_error"));
                }
            }
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            // "Success" for the breaker means the provider answered, whatever the content; transport and credential
            // failures are what open it.
            if (result instanceof AiResult.Failed<TextResponse> failed && (failed.failure() instanceof AiFailure.Transient
                    || failed.failure() instanceof AiFailure.Timeout || failed.failure() instanceof AiFailure.NotConfigured)) {
                breaker.onFailure(ticket);
            } else {
                breaker.onSuccess(ticket);
            }
            settled = true;
            String outcome;
            Usage usage = Usage.ZERO;
            long cost = 0;
            String providerRequestId = null;
            if (result instanceof AiResult.Ok<TextResponse> ok) {
                outcome = "OK";
                usage = ok.value().usage();
                cost = ok.value().costMicros();
                providerRequestId = ok.value().providerRequestId();
                budget.record(capability, cost);
            } else {
                outcome = ((AiResult.Failed<TextResponse>) result).failure().outcome();
            }
            journal.finish(callId, new CallJournal.Outcome(outcome, usage, cost, providerRequestId, latency.toMillis()));
            try {
                telemetry.record(capability, candidate.provider(), candidate.model(), request.stepId(), outcome, latency, usage, cost,
                        candidate.adapter().egress());
            } catch (RuntimeException exception) {
                warn("telemetry", exception, candidate, request);
            }
            return result;
        } finally {
            if (!settled) breaker.release(ticket);
        }
    }

    /** Internal failures are logged by stage and exception class only: messages can carry prompt or provider text. */
    private static void warn(String stage, RuntimeException exception, AiRouting.Candidate candidate, TextRequest request) {
        LOG.warn("ai_call_internal_failure stage={} error_type={} provider={} model={} step_id={}", stage,
                exception.getClass().getSimpleName(), candidate.provider(), candidate.model(),
                request.stepId() == null ? "-" : request.stepId());
    }

    private Duration remaining(Instant deadline) {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Full jitter: uniform in {@code [0, min(cap, base * 2^(attempt-1))]}. */
    private Duration jitter(int attempt) {
        AiProperties.Retry retry = properties.retry();
        long exponential = retry.backoffBase().toMillis() << Math.min(attempt - 1, 20);
        long cap = Math.min(retry.backoffCap().toMillis(), exponential < 0 ? Long.MAX_VALUE : exponential);
        return Duration.ofMillis(random.nextLong(cap + 1));
    }

    private boolean pause(Duration duration) {
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Duration min(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }

    private static Duration max(Duration left, Duration right) { return left.compareTo(right) >= 0 ? left : right; }

    /** Tells the caller's listener when delivered text is abandoned by a retry or a fallback, and notes when it throws. */
    private static final class Delivery implements StreamListener {
        private final StreamListener delegate;
        private boolean delivered;
        private boolean failed;

        Delivery(StreamListener delegate) { this.delegate = delegate; }

        boolean failed() { return failed; }

        @Override
        public void onDelta(String text) {
            delivered = true;
            try {
                delegate.onDelta(text);
            } catch (RuntimeException exception) {
                failed = true;
                throw exception;
            }
        }

        void restart() {
            if (delivered) {
                delivered = false;
                try {
                    delegate.onRestart();
                } catch (RuntimeException exception) {
                    LOG.warn("ai_stream_restart_failed error_type={}", exception.getClass().getSimpleName());
                }
            }
        }
    }
}
