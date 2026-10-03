package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.generation.Rows.Session;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The worker of one process: claims due steps and runs each on its own virtual thread, with a heartbeat on another.
 * It exists only for the {@code worker} and {@code all} roles ({@code learning.runtime.roles}); an {@code api} process
 * creates sessions and serves reads but never executes a step and never needs a provider key.
 *
 * <p>Wake-ups: the {@code afterCommit} of a transaction that created work ({@link #wake}), the end of every run, and the
 * sweeper ({@code learning.generation.worker.sweep-interval}, 2 s), which also recovers expired leases and renews the
 * reservations of running sessions. The table is the source of truth: a lost wake-up costs at most one sweep.
 *
 * <p>Capacity: one permit per capability and instance ({@code learning.ai.permits.*}, text 16), so the dispatcher never
 * runs more steps of a capability than the provider layer would admit, and a soft cap of running steps per account.
 * Only kinds with a registered {@link StepExecutor} are claimed.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class StepDispatcher implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(StepDispatcher.class);
    private static final int RECOVERY_BATCH = 50;

    private final StepQueue queue;
    private final StepRepository steps;
    private final GenerationRepository repository;
    private final SessionLifecycle lifecycle;
    private final EditLifecycle edits;
    private final GenerationSettings settings;
    private final Map<String, StepExecutor> executors;
    /** The executors in the order they are offered work: the interactive EDIT first, so a person waiting for a rewrite is not behind a batch. */
    private final List<StepExecutor> offered;
    private final Map<AiCapability, Semaphore> permits = new EnumMap<>(AiCapability.class);
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private final Map<UUID, UUID> running = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean stopping;
    private volatile Instant lastRenewal = Instant.EPOCH;

    StepDispatcher(StepQueue queue, StepRepository steps, GenerationRepository repository, SessionLifecycle lifecycle,
                   EditLifecycle edits, GenerationSettings settings, AiProperties ai, List<StepExecutor> executors, MeterRegistry meters) {
        this.queue = queue;
        this.steps = steps;
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.edits = edits;
        this.settings = settings;
        this.executors = executors.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(StepExecutor::kind, e -> e));
        this.offered = executors.stream().sorted(java.util.Comparator.comparing(executor -> !executor.kind().equals(EditExecutor.KIND))).toList();
        for (StepExecutor executor : executors) {
            permits.computeIfAbsent(executor.capability(), capability -> new Semaphore(ai.permits().of(capability)));
        }
        // How long the oldest due step has waited for a worker: the signal that capacity or a provider is the bottleneck.
        Gauge.builder("mnema_generation_step_queue_age_seconds", steps, StepDispatcher::queueAge).register(meters);
    }

    private static double queueAge(StepRepository steps) {
        try {
            return steps.oldestDueAgeSeconds();
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    /** Asks for a pass over the queue; coalesced, never blocks the caller. */
    void wake() {
        try {
            threads.execute(this::drain);
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // The context is shutting down; unfinished steps are recovered by their lease.
        }
    }

    @Scheduled(initialDelayString = "${learning.generation.worker.sweep-interval:PT2S}",
            fixedDelayString = "${learning.generation.worker.sweep-interval:PT2S}")
    void sweep() {
        try {
            recoverExpired();
            renewReservations();
        } catch (RuntimeException failure) {
            LOG.warn("generation_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
        drain();
    }

    /** Expired leases return their steps to the queue (or fail them after the last attempt). */
    void recoverExpired() {
        for (UUID step : steps.expiredRunning(RECOVERY_BATCH)) {
            try {
                // an edit step fails its turn, not an artifact: it has its own recovery
                if (steps.step(step).filter(found -> found.kind().equals(EditExecutor.KIND) || found.input().has("turnId")).isPresent()) {
                    edits.recover(step);
                } else {
                    lifecycle.recover(step);
                }
            } catch (RuntimeException failure) {
                LOG.warn("generation_recovery_failed step_id={} error_type={}", step, failure.getClass().getSimpleName());
            }
        }
    }

    private void renewReservations() {
        Instant now = Instant.now();
        if (Duration.between(lastRenewal, now).compareTo(settings.worker().renewInterval()) < 0) return;
        lastRenewal = now;
        // every running session, in pages by id: a busy instance must not renew the first 200 only
        UUID after = new UUID(0, 0);
        while (true) {
            List<Session> page = repository.runningWithReservation(after, 200);
            for (Session session : page) lifecycle.renew(session);
            if (page.size() < 200) return;
            after = page.getLast().sessionId();
        }
    }

    /** Claims and starts steps until nothing is due or every capability is full. Single-flight: a second caller sets a flag. */
    void drain() {
        if (stopping) return;
        if (!draining.compareAndSet(false, true)) {
            again.set(true);
            return;
        }
        try {
            do {
                again.set(false);
                while (startOne()) {
                    // keep claiming while there is capacity and due work
                }
            } while (again.get());
        } catch (RuntimeException failure) {
            LOG.warn("generation_drain_failed error_type={}", failure.getClass().getSimpleName());
        } finally {
            draining.set(false);
        }
        if (again.get()) wake();
    }

    private boolean startOne() {
        for (StepExecutor executor : offered) {
            Semaphore permit = permits.get(executor.capability());
            if (!permit.tryAcquire()) continue;
            Optional<StepClaim> claim;
            try {
                claim = queue.claim(List.of(executor.kind()), executor.requiredInput());
            } catch (RuntimeException failure) {
                permit.release();
                throw failure;
            }
            if (claim.isEmpty()) {
                permit.release();
                continue;
            }
            StepClaim claimed = claim.get();
            threads.execute(() -> {
                try {
                    run(executor, claimed);
                } finally {
                    permit.release();
                    wake();
                }
            });
            return true;
        }
        return false;
    }

    /** One run: the executor on this thread, the heartbeat on a sibling virtual thread. */
    private void run(StepExecutor executor, StepClaim claim) {
        Control control = new Control();
        Thread worker = Thread.currentThread();
        control.worker = worker;
        running.put(claim.stepId(), claim.token());
        Thread heartbeat = Thread.ofVirtual().name("generation-heartbeat").start(() -> heartbeat(claim, control));
        try {
            executor.execute(claim, control);
        } catch (RuntimeException failure) {
            // The step stays RUNNING; its lease expires and the sweeper recovers it (a crash mid-step).
            LOG.error("generation_step_crashed step_id={} session_id={} kind={} error_type={}", claim.stepId(), claim.sessionId(),
                    claim.kind(), failure.getClass().getSimpleName());
        } finally {
            control.finished = true;
            running.remove(claim.stepId());
            heartbeat.interrupt();
            Thread.interrupted();
        }
    }

    private void heartbeat(StepClaim claim, Control control) {
        long lease = Math.max(1, settings.worker().lease().toSeconds());
        int failures = 0;
        while (!control.finished) {
            try {
                Thread.sleep(settings.worker().heartbeat());
            } catch (InterruptedException stopped) {
                return;
            }
            try {
                Optional<Boolean> cancel = steps.heartbeat(claim.stepId(), claim.token(), lease);
                failures = 0;
                if (cancel.isEmpty()) {
                    control.lost = true;
                    control.abortCall();
                    return;
                }
                if (cancel.get() && !control.cancelled) {
                    // Abort the provider call: the owner cancelled the session.
                    control.cancelled = true;
                    control.abortCall();
                }
            } catch (RuntimeException failure) {
                // A database that cannot be reached cannot extend the lease: stop writing, the lease will expire.
                if (++failures >= 2) {
                    control.lost = true;
                    control.abortCall();
                    return;
                }
            }
        }
    }

    /** The flags a heartbeat sets for the executor running the claim. */
    private static final class Control implements StepExecutor.StepControl {
        volatile boolean cancelled;
        volatile boolean lost;
        volatile boolean finished;
        volatile Thread worker;
        private boolean inCall;

        /** Interrupts the worker only while it is inside the provider call; elsewhere the flags are enough. */
        synchronized void abortCall() {
            if (inCall) worker.interrupt();
        }

        @Override
        public synchronized void callStarted() { inCall = true; }

        @Override
        public synchronized void callEnded() {
            inCall = false;
            // a late interrupt from the heartbeat must not reach the database calls that follow
            if (cancelled || lost) Thread.interrupted();
        }

        @Override public boolean cancelled() { return cancelled; }

        @Override public boolean lost() { return lost; }
    }

    /**
     * A clean shutdown: no new claims, a few seconds for the runs to finish, then every run still going is handed back
     * (READY at once, its attempt not counted), so a restart does not wait for the lease or burn an attempt. A run that
     * outlives this writes nothing: its token is gone.
     */
    @Override
    public void destroy() {
        stopping = true;
        threads.shutdown();
        try {
            threads.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (Map.Entry<UUID, UUID> entry : Map.copyOf(running).entrySet()) {
            try {
                steps.release(entry.getKey(), entry.getValue());
            } catch (RuntimeException failure) {
                LOG.warn("generation_shutdown_release_failed step_id={} error_type={}", entry.getKey(), failure.getClass().getSimpleName());
            }
        }
        threads.shutdownNow();
    }
}
