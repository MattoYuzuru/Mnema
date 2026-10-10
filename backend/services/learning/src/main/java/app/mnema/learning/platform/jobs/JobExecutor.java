package app.mnema.learning.platform.jobs;

import app.mnema.learning.platform.wake.WakeTarget;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The executor of {@link LearningJobs}: claims due jobs of the queues that have a {@link JobHandler}, runs each on its own virtual thread as a series of
 * bounded slices, and recovers what a crashed worker left. It exists only for the {@code worker} and {@code all} roles ({@code learning.runtime.roles},
 * like the other workers); an {@code api} process enqueues but never executes. {@code learning.jobs.enabled=false} stops every claim and sweep of
 * the process and {@code learning.jobs.<queue>.enabled=false} stops one queue; the jobs wait in the table.
 *
 * <p><b>Claim and limits.</b> A claim is {@code FOR UPDATE SKIP LOCKED}, oldest due job first, and skips accounts that already have a running job in the queue;
 * the unique index {@code learning_job_account_running} makes that limit of one hold even when two workers claim at the same moment. Each queue runs at
 * most {@code concurrency} jobs in this process and all queues together at most {@code learning.jobs.max-concurrency}, because a running slice holds a
 * pooled connection; queues are offered the shared capacity in rotation.
 *
 * <p><b>Slices, fencing, exactly once.</b> Every slice is one transaction that (1) locks the job row, requiring the lease token the claim issued, (2) runs the
 * handler, (3) writes the cursor and renews the lease, or marks the job {@code SUCCEEDED}, or throws. Because the handler's effects and the executor's
 * write commit together under that row lock, a slice either happened with its cursor or did not happen at all; a worker whose lease was lost (expired and
 * swept, cancelled, the job requeued and taken by another worker) fails step (1), never runs the handler and writes nothing. A job therefore resumes from the
 * last committed cursor after a crash, and its terminal effect is committed exactly once, by the one slice that also set {@code SUCCEEDED}.
 *
 * <p><b>Recovery.</b> A worker that stops renewing (a crash, a kill) is found by the sweep through {@code lease_until}: the job is requeued with a backoff
 * (the lost claim counts as a failed attempt) or ends {@code FAILED} when its attempts are used up. {@code attempts} counts consecutive failures: a slice
 * that commits progress resets it. Retries use the same backoff, doubling per failure up to the cap, each delay with up to 20 % jitter. A failure before the
 * handler ran (no connection, no transaction) is the process's trouble, not the job's: the job is given back after one backoff step and no attempt is
 * spent. A cancellation requested while the job runs is honoured before its next slice. A job that ends {@code FAILED} or {@code CANCELLED} calls
 * {@link JobHandler#onEnded} in the transaction that settles it. Finished jobs are deleted after {@code learning.jobs.retention} in bounded batches.
 *
 * <p><b>Wake-ups.</b> The {@code afterCommit} of an enqueue ({@link LearningJobs}), the {@code NOTIFY} of the {@code V50} trigger when the roles are split
 * ({@link WakeTarget}), the end of every job, and the sweep ({@code learning.jobs.sweep-interval}, 2 s). The table is the source of truth; a lost wake-up costs
 * at most one sweep, and an idle sweep is one index probe per queue.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class JobExecutor implements DisposableBean, WakeTarget {
    private static final Logger LOG = LoggerFactory.getLogger(JobExecutor.class);
    private static final int EXPIRY_BATCH = 100;
    private static final int PURGE_BATCH = 500;
    private static final int PURGE_BATCHES_PER_PASS = 10;
    private static final long PURGE_EVERY_NANOS = Duration.ofMinutes(1).toNanos();
    private static final long GAUGE_CACHE_NANOS = Duration.ofSeconds(5).toNanos();

    private enum Outcome { CONTINUE, DONE, CANCELLED, LOST }

    private record QueueState(JobHandler handler, JobSettings.Queue policy, Semaphore permits, TransactionTemplate transaction) {
    }

    /** What happened in one slice that matters to its failure handling. */
    private static final class SliceContext {
        boolean handlerStarted;
        boolean hookFailed;
    }

    private final JobRepository repository;
    private final JobSettings settings;
    private final MeterRegistry meters;
    private final Map<String, QueueState> queues = new LinkedHashMap<>();
    private final List<String> queueNames = new ArrayList<>();
    private final Semaphore capacity;
    private final AtomicInteger rotation = new AtomicInteger();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile long lastPurge = System.nanoTime() - PURGE_EVERY_NANOS;

    JobExecutor(JobRepository repository, JobSettings settings, ObjectProvider<JobHandler> handlers, MeterRegistry meters,
                PlatformTransactionManager transactions) {
        this.repository = repository;
        this.settings = settings;
        this.meters = meters;
        this.capacity = new Semaphore(settings.maxConcurrency());
        handlers.orderedStream().forEach(handler -> {
            String queue = JobCodes.queue(handler.queue());
            JobSettings.Queue policy = settings.queue(queue);
            TransactionTemplate transaction = new TransactionTemplate(transactions);
            transaction.setTimeout((int) Math.max(1, policy.lease().toSeconds()));
            if (queues.put(queue, new QueueState(handler, policy, new Semaphore(policy.concurrency()), transaction)) != null) {
                throw new IllegalStateException("Two job handlers serve the queue " + queue);
            }
            queueNames.add(queue);
            gauge("mnema_learning_jobs_queue_depth", queue, () -> repository.depth(queue));
            gauge("mnema_learning_jobs_oldest_age_seconds", queue, () -> repository.oldestAgeSeconds(queue));
            LOG.info("learning_job_queue queue={} enabled={} concurrency={} lease={} max_attempts={} backoff={} backoff_max={}", queue, policy.enabled(),
                    policy.concurrency(), policy.lease(), policy.maxAttempts(), policy.backoff(), policy.backoffMax());
        });
        LOG.info("learning_job_executor enabled={} max_concurrency={} sweep_interval={} retention={} queues={}", settings.enabled(), settings.maxConcurrency(),
                settings.sweepInterval(), settings.retention(), queueNames.size());
        for (String key : settings.unmatchedKeys(queues.keySet())) {
            LOG.warn("learning_job_unmatched_setting key={} (no handler serves that queue, or no such setting)", key);
        }
    }

    /** A gauge read from the table when scraped (through the due index), at most every 5 s; a database that cannot be read reports NaN, never a false 0. */
    private void gauge(String name, String queue, DoubleSupplier read) {
        double[] cached = {0};
        long[] at = {System.nanoTime() - GAUGE_CACHE_NANOS - 1};
        Gauge.builder(name, () -> {
            synchronized (cached) {
                if (System.nanoTime() - at[0] > GAUGE_CACHE_NANOS) {
                    try {
                        cached[0] = read.getAsDouble();
                    } catch (RuntimeException unavailable) {
                        cached[0] = Double.NaN;
                    }
                    at[0] = System.nanoTime();
                }
                return cached[0];
            }
        }).tag("queue", queue).register(meters);
    }

    @Override
    public String channel() {
        return "mnema_learning_jobs";
    }

    /** Asks for a pass over the queues; coalesced, never blocks the caller. */
    @Override
    public void wake() {
        try {
            threads.execute(this::drain);
        } catch (RejectedExecutionException closing) {
            // the context is shutting down; a running job's lease expires and another process recovers it
        }
    }

    @Scheduled(initialDelayString = "${learning.jobs.sweep-interval:PT2S}", fixedDelayString = "${learning.jobs.sweep-interval:PT2S}")
    void sweep() {
        if (!settings.enabled() || stopping) return;
        try {
            recoverExpired();
            purge(false);
        } catch (RuntimeException failure) {
            LOG.warn("learning_job_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
        drain();
    }

    /**
     * Requeues (or fails) the jobs of every enabled queue whose lease expired, one job per transaction so that {@link JobHandler#onEnded} runs in the
     * transaction that settles its job. An idle queue costs one probe of the lease index. Returns how many jobs it moved.
     */
    int recoverExpired() {
        int moved = 0;
        for (Map.Entry<String, QueueState> entry : queues.entrySet()) {
            String queue = entry.getKey();
            QueueState state = entry.getValue();
            if (!state.policy().enabled()) continue;
            for (int i = 0; i < EXPIRY_BATCH && repository.hasExpired(queue); i++) {
                List<JobRepository.Moved> expired = settleEnded(state, queue,
                        () -> repository.expireLeases(queue, state.policy().backoff(), state.policy().backoffMax(), 1));
                if (expired.isEmpty()) break;
                for (JobRepository.Moved job : expired) {
                    moved++;
                    count(queue, "expired");
                    countEnd(queue, job.state());
                    LOG.warn("learning_job_lease_expired job_id={} queue={} state={}", job.id(), queue, job.state());
                }
            }
        }
        return moved;
    }

    private void countEnd(String queue, JobState state) {
        if (state == JobState.FAILED) count(queue, "failed");
        if (state == JobState.CANCELLED) count(queue, "cancelled");
    }

    /**
     * Runs {@code settle} (which moves jobs out of {@code RUNNING}) in a transaction of the queue and calls {@link JobHandler#onEnded} in it for every job it
     * ended {@code FAILED} or {@code CANCELLED}. A hook that throws rolls the transaction back and the settlement is repeated without it, so a faulty hook
     * cannot keep a job from ending.
     */
    private List<JobRepository.Moved> settleEnded(QueueState state, String queue, Supplier<List<JobRepository.Moved>> settle) {
        boolean[] hookFailed = {false};
        try {
            return state.transaction().execute(status -> {
                List<JobRepository.Moved> moved = settle.get();
                for (JobRepository.Moved job : moved) {
                    if (job.state() == JobState.FAILED || job.state() == JobState.CANCELLED) {
                        try {
                            state.handler().onEnded(job.job(), job.state());
                        } catch (RuntimeException hook) {
                            hookFailed[0] = true;
                            LOG.error("learning_job_hook_failed job_id={} queue={} error_type={}", job.id(), queue, hook.getClass().getSimpleName());
                            throw hook;
                        }
                    }
                }
                return moved;
            });
        } catch (RuntimeException failure) {
            if (!hookFailed[0]) throw failure;
            count(queue, "hook_failed");
            return settle.get();
        }
    }

    /** Deletes finished jobs past the retention, at most once a minute (unless {@code force}) and in bounded batches. Returns how many. */
    int purge(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastPurge < PURGE_EVERY_NANOS) return 0;
        lastPurge = now;
        int total = 0;
        for (int batch = 0; batch < PURGE_BATCHES_PER_PASS; batch++) {
            int deleted = repository.purge(settings.retention(), PURGE_BATCH);
            total += deleted;
            if (deleted < PURGE_BATCH) break;
        }
        if (total > 0) LOG.info("learning_job_purged count={}", total);
        return total;
    }

    /** Claims and starts jobs until nothing is due or every permit is taken. Single-flight: a second caller sets a flag. */
    void drain() {
        if (stopping || !settings.enabled()) return;
        if (!draining.compareAndSet(false, true)) {
            again.set(true);
            return;
        }
        try {
            do {
                again.set(false);
                int offset = rotation.getAndIncrement();
                for (int i = 0; i < queueNames.size(); i++) {
                    String queue = queueNames.get(Math.floorMod(offset + i, queueNames.size()));
                    try {
                        while (startOne(queue, queues.get(queue))) {
                            // keep claiming while the queue and the process have capacity and the queue has due work
                        }
                    } catch (RuntimeException failure) {
                        LOG.warn("learning_job_drain_failed queue={} error_type={}", queue, failure.getClass().getSimpleName());
                    }
                }
            } while (again.get());
        } finally {
            draining.set(false);
        }
        if (again.get()) wake();
    }

    private boolean startOne(String queue, QueueState state) {
        if (stopping || !state.policy().enabled() || !state.permits().tryAcquire()) return false;
        if (!capacity.tryAcquire()) {
            state.permits().release();
            return false;
        }
        Optional<JobRepository.Claimed> claim;
        try {
            claim = repository.claim(queue, UUID.randomUUID(), state.policy().lease());
        } catch (RuntimeException failure) {
            releasePermits(state);
            throw failure;
        }
        if (claim.isEmpty()) {
            releasePermits(state);
            return false;
        }
        try {
            threads.execute(() -> {
                try {
                    run(claim.get(), state);
                } finally {
                    releasePermits(state);
                    wake();
                }
            });
        } catch (RejectedExecutionException closing) {
            // shutting down between the claim and the start: hand the job back at once instead of leaving it to the lease
            repository.release(claim.get().id(), claim.get().token(), Duration.ZERO);
            releasePermits(state);
            return false;
        }
        return true;
    }

    private void releasePermits(QueueState state) {
        capacity.release();
        state.permits().release();
    }

    /**
     * Claims one due job of {@code queue} and runs it to its end on the calling thread; false when nothing was due or the queue is disabled. This is what
     * {@link #drain} does on virtual threads, exposed for tests that need a deterministic order.
     */
    boolean runOne(String queue) {
        QueueState state = queues.get(queue);
        if (state == null || !state.policy().enabled()) return false;
        Optional<JobRepository.Claimed> claim = repository.claim(queue, UUID.randomUUID(), state.policy().lease());
        claim.ifPresent(claimed -> run(claimed, state));
        return claim.isPresent();
    }

    /** Runs a job somebody claimed with {@link JobRepository#claim}, as a worker would; for tests that stage a lost lease. */
    void runClaimed(JobRepository.Claimed claimed) {
        run(claimed, queues.get(claimed.queue()));
    }

    /** One job, from its claim to its end: slices until the handler is done, the job is cancelled, fails, loses its lease or the process stops. */
    private void run(JobRepository.Claimed claimed, QueueState state) {
        String queue = claimed.queue();
        try {
            while (true) {
                if (stopping) {
                    if (repository.release(claimed.id(), claimed.token(), Duration.ZERO)) count(queue, "released");
                    return;
                }
                if (slice(claimed, state) != Outcome.CONTINUE) return;
            }
        } catch (RuntimeException failure) {
            LOG.error("learning_job_crashed job_id={} queue={} error_type={}", claimed.id(), queue, failure.getClass().getSimpleName());
        }
    }

    private Outcome slice(JobRepository.Claimed claimed, QueueState state) {
        String queue = claimed.queue();
        SliceContext context = new SliceContext();
        long started = System.nanoTime();
        try {
            Outcome outcome = state.transaction().execute(status -> inSlice(claimed, state, context));
            if (outcome == Outcome.DONE) {
                count(queue, "succeeded");
                LOG.info("learning_job_done job_id={} queue={} outcome=SUCCEEDED attempt={}", claimed.id(), queue, claimed.attempt());
            } else if (outcome == Outcome.CANCELLED) {
                count(queue, "cancelled");
                LOG.info("learning_job_done job_id={} queue={} outcome=CANCELLED", claimed.id(), queue);
            } else if (outcome == Outcome.LOST) {
                count(queue, "lost");
                LOG.warn("learning_job_lease_lost job_id={} queue={}", claimed.id(), queue);
            }
            return outcome;
        } catch (RuntimeException failure) {
            if (context.hookFailed) {
                // the cancellation's hook threw: end the job anyway, without the compensation
                count(queue, "hook_failed");
                if (repository.cancelled(claimed.id(), claimed.token())) count(queue, "cancelled");
            } else if (!context.handlerStarted) {
                giveBack(claimed, state, failure);
            } else if (failure instanceof PermanentJobException permanent) {
                settle(claimed, state, permanent.code(), true, failure);
            } else if (failure instanceof RetryableJobException retryable) {
                settle(claimed, state, retryable.code(), false, failure);
            } else {
                settle(claimed, state, JobCodes.HANDLER_ERROR, false, failure);
            }
            return Outcome.LOST;
        } finally {
            meters.timer("mnema_learning_jobs_slice_seconds", "queue", queue).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** The transaction of one slice. Anything thrown here rolls the handler's effects back and the failure is settled in a transaction of its own. */
    private Outcome inSlice(JobRepository.Claimed claimed, QueueState state, SliceContext context) {
        Optional<JobRepository.Locked> locked = repository.lock(claimed.id(), claimed.token());
        if (locked.isEmpty()) return Outcome.LOST;
        Job job = new Job(claimed.id(), claimed.queue(), claimed.accountId(), claimed.payload(), locked.get().progress(), locked.get().progressCount(),
                claimed.attempt());
        if (locked.get().cancelRequested()) {
            repository.cancelled(claimed.id(), claimed.token());
            try {
                state.handler().onEnded(job, JobState.CANCELLED);
            } catch (RuntimeException hook) {
                context.hookFailed = true;
                LOG.error("learning_job_hook_failed job_id={} queue={} error_type={}", claimed.id(), claimed.queue(), hook.getClass().getSimpleName());
                throw hook;
            }
            return Outcome.CANCELLED;
        }
        context.handlerStarted = true;
        Slice result = state.handler().run(job);
        if (result instanceof Slice.Continue next) {
            if (!repository.progress(claimed.id(), claimed.token(), JobJson.write(next.progress(), "progress"), next.progressCount(), state.policy().lease())) {
                throw new IllegalStateException("The lease of a locked job disappeared");
            }
            return Outcome.CONTINUE;
        }
        if (result instanceof Slice.Done done) {
            if (!repository.succeed(claimed.id(), claimed.token(), done.progressCount())) throw new IllegalStateException("The lease of a locked job disappeared");
            return Outcome.DONE;
        }
        throw new IllegalStateException("A job handler returned no slice");
    }

    /**
     * The slice failed before the handler ran (no transaction could be created, no connection, a timeout, a lock): that is the process's trouble, not the
     * job's, so the job goes back to the queue after one backoff step without spending an attempt.
     */
    private void giveBack(JobRepository.Claimed claimed, QueueState state, RuntimeException failure) {
        String queue = claimed.queue();
        try {
            if (repository.release(claimed.id(), claimed.token(), state.policy().backoff())) count(queue, "released");
            LOG.warn("learning_job_given_back job_id={} queue={} error_type={}", claimed.id(), queue, failure.getClass().getSimpleName());
        } catch (RuntimeException unsettled) {
            // the database is unavailable: the lease expires and the sweep settles the job
            LOG.error("learning_job_settle_failed job_id={} queue={} error_type={}", claimed.id(), queue, unsettled.getClass().getSimpleName());
        }
    }

    private void settle(JobRepository.Claimed claimed, QueueState state, String code, boolean permanent, RuntimeException failure) {
        String queue = claimed.queue();
        try {
            List<JobRepository.Moved> moved = settleEnded(state, queue, () -> repository.settleFailure(claimed.id(), claimed.token(), code, permanent,
                    state.policy().backoff(), state.policy().backoffMax()).stream().toList());
            if (moved.isEmpty()) {
                count(queue, "lost");
                return;
            }
            JobState next = moved.get(0).state();
            if (next == JobState.QUEUED) count(queue, "retried");
            else countEnd(queue, next);
            LOG.warn("learning_job_slice_failed job_id={} queue={} attempt={} code={} error_type={} next_state={}", claimed.id(), queue, claimed.attempt(), code,
                    failure.getClass().getSimpleName(), next);
        } catch (RuntimeException unsettled) {
            // the database is unavailable: the lease expires and the sweep settles the job like a crashed worker's
            LOG.error("learning_job_settle_failed job_id={} queue={} error_type={}", claimed.id(), queue, unsettled.getClass().getSimpleName());
        }
    }

    private void count(String queue, String outcome) {
        meters.counter("mnema_learning_jobs_total", "queue", queue, "outcome", outcome).increment();
    }

    /** A clean shutdown: no new claims; a running job returns its lease between slices and one that does not within a few seconds is recovered by the lease sweep. */
    @Override
    public void destroy() {
        stopping = true;
        threads.shutdown();
        try {
            if (!threads.awaitTermination(5, TimeUnit.SECONDS)) threads.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            threads.shutdownNow();
        }
    }
}
