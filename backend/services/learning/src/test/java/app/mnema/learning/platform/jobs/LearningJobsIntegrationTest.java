package app.mnema.learning.platform.jobs;

import app.mnema.learning.platform.jobs.JobRepository.Claimed;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static app.mnema.learning.platform.jobs.JobTestSupport.JSON;
import static app.mnema.learning.platform.jobs.JobTestSupport.effects;
import static app.mnema.learning.platform.jobs.JobTestSupport.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The job executor against a real PostgreSQL. The process's own executor has no handler here (so it never claims anything); each test builds the executors
 * it needs by hand, which makes every order of claims, crashes and lease losses deterministic. The wake-ups, the roles and the kill switch are in
 * {@link JobWorkerIntegrationTest}, {@link JobApiRoleIntegrationTest} and {@link JobKillSwitchIntegrationTest}.
 */
@SpringBootTest(properties = {"learning.jobs.sweep-interval=PT5M", "learning.jobs.retry.max-attempts=3", "learning.jobs.retry.backoff=PT2S",
        "learning.jobs.retry.backoff-max=PT3S", "learning.jobs.lease.max-attempts=2", "learning.jobs.lease.backoff=PT0.2S", "learning.jobs.policy3.max-attempts=3", "learning.jobs.off.enabled=false",
        "learning.jobs.serial.concurrency=8", "learning.jobs.max-concurrency=2", "learning.jobs.gcap.concurrency=8",
        "learning.jobs.consec.max-attempts=2", "spring.datasource.hikari.maximum-pool-size=24"})
class LearningJobsIntegrationTest extends PostgresIntegrationTest {
    /** A worker killed in the middle of a slice: not an exception any handler could catch. */
    private static final class SimulatedKill extends Error {
        private static final long serialVersionUID = 1L;
    }

    @Autowired private LearningJobs jobs;
    @Autowired private JobRepository repository;
    @Autowired private JobSettings settings;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private final List<JobExecutor> executors = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void effectTable(@Autowired JdbcClient jdbc) {
        JobTestSupport.createEffectTable(jdbc);
    }

    private JobExecutor executor(SimpleMeterRegistry meters, JobHandler... handlers) {
        return executor(meters, transactions, handlers);
    }

    private JobExecutor executor(SimpleMeterRegistry meters, PlatformTransactionManager manager, JobHandler... handlers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < handlers.length; i++) beans.addBean("handler" + i, handlers[i]);
        JobExecutor executor = new JobExecutor(repository, settings, beans.getBeanProvider(JobHandler.class), meters, manager);
        executors.add(executor);
        return executor;
    }

    private JobExecutor executor(JobHandler... handlers) {
        return executor(new SimpleMeterRegistry(), handlers);
    }

    @org.junit.jupiter.api.AfterEach
    void stopExecutors() {
        executors.forEach(JobExecutor::destroy);
        executors.clear();
    }

    private static String queue(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID enqueue(String queue, String key, UUID account, int steps) {
        return jobs.enqueue(queue, key, account, payload(steps));
    }

    private String state(UUID job) {
        return jdbc.sql("SELECT state FROM app_learning.learning_job WHERE job_id=:id").param("id", job).query(String.class).single();
    }

    private void makeDue(UUID job) {
        jdbc.sql("UPDATE app_learning.learning_job SET run_after = clock_timestamp() - interval '1 second' WHERE job_id=:id").param("id", job).update();
    }

    private void expireLease(UUID job) {
        jdbc.sql("UPDATE app_learning.learning_job SET lease_until = clock_timestamp() - interval '1 second' WHERE job_id=:id").param("id", job).update();
    }

    private double backoffSeconds(UUID job) {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM (run_after - updated_at))::float8 FROM app_learning.learning_job WHERE job_id=:id").param("id", job)
                .query(Double.class).single();
    }

    // ------------------------------------------------------------------ enqueue

    @Test
    void enqueueIsIdempotentPerQueueAndKeyAndNamesAVersion7Job() {
        String queue = queue("idem");
        UUID account = UUID.randomUUID();

        UUID first = enqueue(queue, "k1", account, 1);
        UUID again = enqueue(queue, "k1", account, 1);
        UUID other = enqueue(queue, "k2", account, 1);
        UUID otherQueue = enqueue(queue("idem-b"), "k1", account, 1);

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
        assertThat(otherQueue).isNotEqualTo(first);
        assertThat(first.version()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue=:q").param("q", queue).query(Long.class).single()).isEqualTo(2L);
        JobStatus status = jobs.status(first).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.QUEUED);
        assertThat(status.queue()).isEqualTo(queue);
        assertThat(status.accountId()).isEqualTo(account);
        assertThat(status.attempts()).isZero();
        assertThat(status.maxAttempts()).isEqualTo(5);
        assertThat(status.progress()).isNull();
        assertThat(status.progressCount()).isZero();
        assertThat(status.cancelRequested()).isFalse();
        assertThat(status.finishedAt()).isNull();
        assertThat(jobs.status(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theAttemptsOfAJobAreThePolicyOfItsQueueAtEnqueueTime() {
        assertThat(jobs.status(enqueue("policy3", "p-" + UUID.randomUUID(), null, 1)).orElseThrow().maxAttempts()).isEqualTo(3);
    }

    @Test
    void theSameKeyForAnotherAccountOrPayloadIsAConflictAndChangesNothing() {
        String queue = queue("conflict");
        UUID account = UUID.randomUUID();
        UUID job = enqueue(queue, "k", account, 2);

        assertThatThrownBy(() -> enqueue(queue, "k", account, 3)).isInstanceOf(JobIdentityConflictException.class);
        assertThatThrownBy(() -> enqueue(queue, "k", UUID.randomUUID(), 2)).isInstanceOf(JobIdentityConflictException.class);
        assertThatThrownBy(() -> enqueue(queue, "k", null, 2)).isInstanceOf(JobIdentityConflictException.class);
        assertThat(enqueue(queue, "k", account, 2)).isEqualTo(job);
    }

    @Test
    void concurrentEnqueuesOfOneKeyCreateOneJob() throws Exception {
        String queue = queue("race");
        UUID account = UUID.randomUUID();
        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    return enqueue(queue, "same", account, 1);
                }));
            }
            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> result : results) ids.add(result.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue=:q").param("q", queue).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void enqueueJoinsTheCallersTransactionSoARollbackTakesTheJobWithIt() {
        String queue = queue("atomic");
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        UUID rolledBack = transaction.execute(status -> {
            UUID job = enqueue(queue, "k", null, 1);
            status.setRollbackOnly();
            return job;
        });
        UUID committed = transaction.execute(status -> enqueue(queue, "k", null, 1));

        assertThat(jobs.status(rolledBack)).as("rolled back with the caller's transaction").isEmpty();
        assertThat(jobs.status(committed)).isPresent();
        assertThat(committed).isNotEqualTo(rolledBack);
    }

    @Test
    void argumentsOutsideTheirBoundsAreRefused() {
        assertThatThrownBy(() -> jobs.enqueue("Bad Queue", "k", null, payload(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.enqueue(null, "k", null, payload(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.enqueue("q", "", null, payload(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.enqueue("q", null, null, payload(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.enqueue("q", "k".repeat(201), null, payload(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.enqueue("q", "k", null, null)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode large = JSON.createObjectNode().put("x", "a".repeat(JobJson.MAX_BYTES));
        assertThatThrownBy(() -> jobs.enqueue("q", "k", null, large)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.enqueue(queue("bound"), "k".repeat(200), null, JSON.createObjectNode().put("x", "a".repeat(JobJson.MAX_BYTES - 8)))).isNotNull();
    }

    // ------------------------------------------------------------------ steps, progress and the end

    @Test
    void aJobRunsInBoundedSlicesCommitsItsCursorAndEndsOnce() {
        String queue = queue("steps");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<Integer> seenAttempts = new ArrayList<>();
        ScriptedHandler handler = new ScriptedHandler(queue, JobTestSupport.steps(jdbc, (job, step) -> seenAttempts.add(job.attempt())));
        JobExecutor executor = executor(meters, handler);
        UUID job = enqueue(queue, "k", UUID.randomUUID(), 4);

        assertThat(executor.runOne(queue)).isTrue();
        assertThat(executor.runOne(queue)).isFalse();

        JobStatus status = jobs.status(job).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(status.attempts()).as("attempts count failures").isZero();
        assertThat(status.progressCount()).isEqualTo(4);
        assertThat(status.progress().path("next").intValue(-1)).isEqualTo(3);
        assertThat(status.lastError()).isNull();
        assertThat(status.finishedAt()).isNotNull();
        assertThat(effects(jdbc, job)).containsExactly(0, 1, 2, 3);
        assertThat(handler.calls).hasValue(4);
        assertThat(seenAttempts).containsOnly(1);
        assertThat(jdbc.sql("SELECT lease_token IS NULL AND lease_until IS NULL FROM app_learning.learning_job WHERE job_id=:id").param("id", job)
                .query(Boolean.class).single()).isTrue();
        assertThat(meters.get("mnema_learning_jobs_total").tag("queue", queue).tag("outcome", "succeeded").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("mnema_learning_jobs_slice_seconds").tag("queue", queue).timer().count()).isEqualTo(4);
    }

    @Test
    void aSliceSeesThePayloadTheAccountAndTheCursorOfTheLastCommit() {
        String queue = queue("view");
        UUID account = UUID.randomUUID();
        List<Job> seen = new ArrayList<>();
        JobExecutor executor = executor(new ScriptedHandler(queue, job -> {
            seen.add(job);
            return seen.size() < 2 ? Slice.proceed(JSON.createObjectNode().put("cursor", "row-42"), 42) : Slice.done(50);
        }));
        UUID id = enqueue(queue, "k", account, 9);

        executor.runOne(queue);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).extracting(Job::id, Job::queue, Job::accountId, Job::progress, Job::progressCount, Job::attempt)
                .containsExactly(id, queue, account, null, 0L, 1);
        assertThat(seen.get(0).payload().path("steps").intValue(0)).isEqualTo(9);
        assertThat(seen.get(1).progress().path("cursor").stringValue("")).isEqualTo("row-42");
        assertThat(seen.get(1).progressCount()).isEqualTo(42);
        assertThat(jobs.status(id).orElseThrow().progressCount()).isEqualTo(50);
    }

    // ------------------------------------------------------------------ crash, lease and fencing

    @Test
    void aKilledWorkerResumesFromItsLastCommittedCursorAndTheTerminalEffectHappensOnce() {
        String queue = queue("crash");
        AtomicInteger kills = new AtomicInteger();
        List<Integer> resumedFrom = new ArrayList<>();
        ScriptedHandler workerA = new ScriptedHandler(queue, JobTestSupport.steps(jdbc, (job, step) -> {
            // the effect of step 2 is written, then the process dies before the slice commits
            if (step == 2 && kills.getAndIncrement() == 0) throw new SimulatedKill();
        }));
        ScriptedHandler workerB = new ScriptedHandler(queue, job -> {
            if (resumedFrom.isEmpty()) resumedFrom.add(job.progress().path("next").intValue(-1));
            return JobTestSupport.steps(jdbc).apply(job);
        });
        SimpleMeterRegistry metersB = new SimpleMeterRegistry();
        JobExecutor a = executor(workerA);
        JobExecutor b = executor(metersB, workerB);
        UUID job = enqueue(queue, "k", UUID.randomUUID(), 4);

        assertThatThrownBy(() -> a.runOne(queue)).isInstanceOf(SimulatedKill.class);

        assertThat(state(job)).as("the process is gone, the lease is still held").isEqualTo("RUNNING");
        assertThat(effects(jdbc, job)).as("slice 2 rolled back with its cursor").containsExactly(0, 1);
        assertThat(jobs.status(job).orElseThrow().progressCount()).isEqualTo(2);
        assertThat(b.recoverExpired()).as("the lease has not expired yet").isZero();

        expireLease(job);
        assertThat(b.recoverExpired()).isEqualTo(1);
        JobStatus requeued = jobs.status(job).orElseThrow();
        assertThat(requeued.state()).isEqualTo(JobState.QUEUED);
        assertThat(requeued.lastError()).isEqualTo("lease_expired");
        assertThat(requeued.attempts()).isEqualTo(1);
        assertThat(b.runOne(queue)).as("the backoff has not passed").isFalse();
        assertThat(backoffSeconds(job)).as("10 s, shortened by at most 20 % of jitter").isBetween(7.9, 10.01);

        makeDue(job);
        assertThat(b.runOne(queue)).isTrue();

        JobStatus status = jobs.status(job).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(status.attempts()).as("the slices that committed forgave the failure").isZero();
        assertThat(status.lastError()).isNull();
        assertThat(resumedFrom).as("resumed from the committed cursor, not from the start").containsExactly(2);
        assertThat(effects(jdbc, job)).as("every step exactly once, the terminal one included").containsExactly(0, 1, 2, 3);
        assertThat(metersB.get("mnema_learning_jobs_total").tag("outcome", "expired").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aStaleWorkerNeitherRunsTheHandlerNorWritesAnything() {
        String queue = queue("stale");
        ScriptedHandler staleHandler = new ScriptedHandler(queue, JobTestSupport.steps(jdbc));
        ScriptedHandler currentHandler = new ScriptedHandler(queue, JobTestSupport.steps(jdbc));
        SimpleMeterRegistry staleMeters = new SimpleMeterRegistry();
        JobExecutor stale = executor(staleMeters, staleHandler);
        JobExecutor current = executor(currentHandler);
        UUID job = enqueue(queue, "k", UUID.randomUUID(), 2);
        Duration lease = Duration.ofSeconds(60);

        Claimed old = repository.claim(queue, UUID.randomUUID(), lease).orElseThrow();
        expireLease(job);
        assertThat(current.recoverExpired()).isEqualTo(1);
        makeDue(job);
        Claimed fresh = repository.claim(queue, UUID.randomUUID(), lease).orElseThrow();
        assertThat(fresh.attempt()).isEqualTo(2);

        stale.runClaimed(old);

        assertThat(staleHandler.calls).hasValue(0);
        assertThat(effects(jdbc, job)).isEmpty();
        assertThat(state(job)).isEqualTo("RUNNING");
        assertThat(staleMeters.get("mnema_learning_jobs_total").tag("outcome", "lost").counter().count()).isEqualTo(1.0);
        // every write of the lost lease is refused by the database condition, not by the caller's good manners
        assertThat(repository.progress(job, old.token(), "{}", 9, lease)).isFalse();
        assertThat(repository.succeed(job, old.token(), 9)).isFalse();
        assertThat(repository.cancelled(job, old.token())).isFalse();
        assertThat(repository.release(job, old.token(), Duration.ZERO)).isFalse();
        assertThat(repository.lock(job, old.token())).isEmpty();
        assertThat(repository.settleFailure(job, old.token(), "x", true, lease, lease)).isEmpty();

        current.runClaimed(fresh);

        assertThat(state(job)).isEqualTo("SUCCEEDED");
        assertThat(effects(jdbc, job)).containsExactly(0, 1);
        assertThat(currentHandler.calls).hasValue(2);
    }

    @Test
    void anExpiredLeaseCountsAsAnAttemptAndTheLastOneFailsTheJob() {
        String queue = "lease";
        JobExecutor executor = executor(new ScriptedHandler(queue, job -> Slice.done(0)));
        UUID job = jobs.enqueue(queue, "k-" + UUID.randomUUID(), null, payload(1));
        Duration lease = Duration.ofSeconds(60);

        repository.claim(queue, UUID.randomUUID(), lease).orElseThrow();
        expireLease(job);
        assertThat(executor.recoverExpired()).isEqualTo(1);
        assertThat(state(job)).isEqualTo("QUEUED");
        assertThat(backoffSeconds(job)).as("0.2 s with jitter").isBetween(0.15, 0.21);

        makeDue(job);
        repository.claim(queue, UUID.randomUUID(), lease).orElseThrow();
        expireLease(job);
        assertThat(executor.recoverExpired()).isEqualTo(1);

        JobStatus status = jobs.status(job).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.attempts()).isEqualTo(2);
        assertThat(status.lastError()).isEqualTo("lease_expired");
        assertThat(status.finishedAt()).isNotNull();
        assertThat(executor.runOne(queue)).isFalse();
    }

    @Test
    void aCleanShutdownGivesTheJobBackBetweenSlicesWithoutSpendingAnAttempt() {
        String queue = queue("stop");
        JobExecutor[] holder = new JobExecutor[1];
        ScriptedHandler handler = new ScriptedHandler(queue, JobTestSupport.steps(jdbc, (job, step) -> holder[0].destroy()));
        holder[0] = executor(new SimpleMeterRegistry(), handler);
        UUID job = enqueue(queue, "k", null, 5);

        holder[0].runOne(queue);

        JobStatus status = jobs.status(job).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.QUEUED);
        assertThat(status.attempts()).isZero();
        assertThat(status.progressCount()).isEqualTo(1);
        assertThat(effects(jdbc, job)).containsExactly(0);
        JobExecutor next = executor(new ScriptedHandler(queue, JobTestSupport.steps(jdbc)));
        assertThat(next.runOne(queue)).isTrue();
        assertThat(effects(jdbc, job)).containsExactly(0, 1, 2, 3, 4);
        assertThat(state(job)).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------ failures and backoff

    @Test
    void aRetryableFailureRollsTheSliceBackAndBacksOffExponentiallyUpToTheCapThenFails() {
        String queue = "retry";
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScriptedHandler handler = new ScriptedHandler(queue, job -> {
            JobTestSupport.steps(jdbc).apply(job);
            throw new RetryableJobException("storage_busy");
        });
        JobExecutor executor = executor(meters, handler);
        UUID job = jobs.enqueue(queue, "k-" + UUID.randomUUID(), null, payload(3));

        assertThat(executor.runOne(queue)).isTrue();
        JobStatus first = jobs.status(job).orElseThrow();
        assertThat(first.state()).isEqualTo(JobState.QUEUED);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.lastError()).isEqualTo("storage_busy");
        assertThat(effects(jdbc, job)).as("the failed slice left no effect").isEmpty();
        assertThat(backoffSeconds(job)).isBetween(1.55, 2.01);
        assertThat(executor.runOne(queue)).as("not due before its backoff").isFalse();

        makeDue(job);
        assertThat(executor.runOne(queue)).isTrue();
        assertThat(jobs.status(job).orElseThrow().attempts()).isEqualTo(2);
        assertThat(backoffSeconds(job)).as("2 s doubled, held at the 3 s cap").isBetween(2.35, 3.01);

        makeDue(job);
        assertThat(executor.runOne(queue)).isTrue();
        JobStatus last = jobs.status(job).orElseThrow();
        assertThat(last.state()).isEqualTo(JobState.FAILED);
        assertThat(last.attempts()).isEqualTo(3);
        assertThat(last.lastError()).isEqualTo("storage_busy");
        assertThat(last.finishedAt()).isNotNull();
        assertThat(executor.runOne(queue)).isFalse();
        assertThat(effects(jdbc, job)).isEmpty();
        assertThat(meters.get("mnema_learning_jobs_total").tag("outcome", "retried").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("mnema_learning_jobs_total").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aPermanentFailureEndsTheJobAtOnceAndAnUnexpectedExceptionIsRetriedWithAGenericCode() {
        String queue = queue("fail");
        JobExecutor executor = executor(new ScriptedHandler(queue, job -> {
            if (job.payload().path("steps").intValue(0) == 1) throw new PermanentJobException("source_gone");
            throw new IllegalStateException("contains a secret that must not be stored");
        }));
        UUID permanent = enqueue(queue, "permanent", null, 1);
        UUID unexpected = enqueue(queue, "unexpected", null, 2);

        executor.runOne(queue);
        executor.runOne(queue);

        JobStatus failed = jobs.status(permanent).orElseThrow();
        assertThat(failed.state()).isEqualTo(JobState.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("source_gone");
        JobStatus retried = jobs.status(unexpected).orElseThrow();
        assertThat(retried.state()).isEqualTo(JobState.QUEUED);
        assertThat(retried.lastError()).isEqualTo("handler_error");
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    void aQueuedJobIsCancelledAtOnceAndNeverRuns() {
        String queue = queue("cancel-queued");
        ScriptedHandler handler = new ScriptedHandler(queue, job -> Slice.done(0));
        JobExecutor executor = executor(handler);
        UUID job = enqueue(queue, "k", null, 1);

        assertThat(jobs.cancel(job)).contains(JobState.CANCELLED);

        assertThat(executor.runOne(queue)).isFalse();
        assertThat(handler.calls).hasValue(0);
        assertThat(jobs.status(job).orElseThrow().finishedAt()).isNotNull();
        assertThat(jobs.cancel(job)).as("a finished job stays as it is").contains(JobState.CANCELLED);
        assertThat(jobs.cancel(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aRunningJobIsCancelledBeforeItsNextSlice() {
        String queue = queue("cancel-running");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID[] id = new UUID[1];
        ScriptedHandler handler = new ScriptedHandler(queue, JobTestSupport.steps(jdbc, (job, step) -> {
            // the request arrives while the first slice runs
            if (step == 0) assertThat(jobs.cancel(job.id())).contains(JobState.RUNNING);
        }));
        JobExecutor executor = executor(meters, handler);
        id[0] = enqueue(queue, "k", null, 5);

        executor.runOne(queue);

        JobStatus status = jobs.status(id[0]).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.CANCELLED);
        assertThat(status.cancelRequested()).isTrue();
        assertThat(handler.calls).as("the slice in flight completed, no further one started").hasValue(1);
        assertThat(effects(jdbc, id[0])).containsExactly(0);
        assertThat(meters.get("mnema_learning_jobs_total").tag("outcome", "cancelled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aCancellationWaitingWhenTheClaimIsLostOrTheSliceFailsStillWins() {
        String queue = queue("cancel-wins");
        ScriptedHandler handler = new ScriptedHandler(queue, job -> {
            throw new RetryableJobException("again");
        });
        JobExecutor executor = executor(handler);
        UUID viaFailure = enqueue(queue, "failure", null, 1);
        UUID viaExpiry = enqueue(queue, "expiry", null, 1);

        Claimed first = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();
        Claimed second = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();
        assertThat(jobs.cancel(viaFailure)).contains(JobState.RUNNING);
        assertThat(jobs.cancel(viaExpiry)).contains(JobState.RUNNING);
        // the first job's worker fails its slice; the second's worker dies
        assertThat(repository.settleFailure(first.id(), first.token(), "again", false, Duration.ofSeconds(1), Duration.ofSeconds(1))).isPresent();
        expireLease(second.id());
        assertThat(executor.recoverExpired()).isEqualTo(1);

        assertThat(state(first.id())).isEqualTo("CANCELLED");
        assertThat(state(second.id())).isEqualTo("CANCELLED");
        assertThat(handler.calls).hasValue(0);
    }

    @Test
    void aClaimedJobCancelledBeforeItsFirstSliceEndsCancelledWithoutRunningTheHandler() {
        String queue = queue("cancel-claimed");
        ScriptedHandler handler = new ScriptedHandler(queue, job -> Slice.done(0));
        JobExecutor executor = executor(handler);
        UUID job = enqueue(queue, "k", null, 1);
        Claimed claimed = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();

        assertThat(jobs.cancel(job)).contains(JobState.RUNNING);
        executor.runClaimed(claimed);

        assertThat(state(job)).isEqualTo("CANCELLED");
        assertThat(handler.calls).hasValue(0);
    }

    // ------------------------------------------------------------------ kill switch

    @Test
    void aDisabledQueueIsNeitherClaimedNorDrained() throws Exception {
        String queue = "off";
        ScriptedHandler handler = new ScriptedHandler(queue, job -> Slice.done(0));
        JobExecutor executor = executor(handler);
        UUID job = jobs.enqueue(queue, "k-" + UUID.randomUUID(), null, payload(1));

        assertThat(executor.runOne(queue)).isFalse();
        executor.drain();
        Thread.sleep(300);

        assertThat(state(job)).isEqualTo("QUEUED");
        assertThat(handler.calls).hasValue(0);
        assertThat(executor.runOne("unknown-queue")).isFalse();
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void parallelClaimsNeverRunTwoJobsOfOneAccountInAQueue() throws Exception {
        for (int round = 0; round < 5; round++) {
            String queue = queue("claims");
            List<UUID> accounts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            for (UUID account : accounts) for (int i = 0; i < 5; i++) enqueue(queue, account + "-" + i, account, 1);
            int threads = 12;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Claimed> claimed = new CopyOnWriteArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).ifPresent(claimed::add);
                        return null;
                    }));
                }
                for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            }

            assertThat(claimed).extracting(Claimed::accountId).containsExactlyInAnyOrderElementsOf(accounts);
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue=:q AND state='RUNNING'").param("q", queue)
                    .query(Long.class).single()).isEqualTo(3L);
            assertThat(repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60))).as("every account is busy").isEmpty();
        }
    }

    @Test
    void theDatabaseItselfRefusesASecondRunningJobOfOneAccountAndQueue() {
        String queue = queue("index");
        UUID account = UUID.randomUUID();
        UUID first = enqueue(queue, "1", account, 1);
        UUID second = enqueue(queue, "2", account, 1);
        UUID otherQueueJob = enqueue(queue("index-b"), "1", account, 1);
        UUID system1 = enqueue(queue, "s1", null, 1);
        UUID system2 = enqueue(queue, "s2", null, 1);
        String run = "UPDATE app_learning.learning_job SET state='RUNNING', lease_token=gen_random_uuid(), lease_until=clock_timestamp() + interval '1 minute' WHERE job_id=:id";

        jdbc.sql(run).param("id", first).update();

        assertThatThrownBy(() -> jdbc.sql(run).param("id", second).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql(run).param("id", otherQueueJob).update()).as("another queue is another resource").isEqualTo(1);
        assertThat(jdbc.sql(run).param("id", system1).update() + jdbc.sql(run).param("id", system2).update()).as("a system job has no account limit").isEqualTo(2);
    }

    @Test
    void aBlockedAccountDoesNotBlockTheQueueBehindIt() {
        String queue = queue("fair");
        UUID busy = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID head = enqueue(queue, "busy-1", busy, 1);
        enqueue(queue, "busy-2", busy, 1);
        UUID behind = enqueue(queue, "other", other, 1);

        Claimed first = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();
        Claimed second = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();

        assertThat(first.id()).isEqualTo(head);
        assertThat(second.id()).as("the second job of the busy account is skipped").isEqualTo(behind);
        assertThat(repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60))).isEmpty();
    }

    @Test
    void twoExecutorsRunManyJobsWithAtMostOneJobPerAccountAtATime() throws Exception {
        String queue = "serial";
        ConcurrentHashMap<UUID, AtomicInteger> running = new ConcurrentHashMap<>();
        AtomicInteger violations = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        CountDownLatch together = new CountDownLatch(2);
        java.util.function.Function<Job, Slice> body = job -> {
            // the first two slices wait for each other: they can only both be in flight when two accounts really run in parallel
            together.countDown();
            try {
                assertThat(together.await(30, TimeUnit.SECONDS)).as("two jobs of different accounts were never in flight together").isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            int concurrent = job.accountId() == null ? 1 : running.computeIfAbsent(job.accountId(), key -> new AtomicInteger()).incrementAndGet();
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                if (concurrent > 1) violations.incrementAndGet();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return JobTestSupport.steps(jdbc).apply(job);
            } finally {
                inFlight.decrementAndGet();
                if (job.accountId() != null) running.get(job.accountId()).decrementAndGet();
            }
        };
        JobExecutor a = executor(new ScriptedHandler(queue, body));
        JobExecutor b = executor(new ScriptedHandler(queue, body));
        String prefix = UUID.randomUUID().toString();
        List<UUID> all = new ArrayList<>();
        for (int account = 0; account < 3; account++) {
            UUID id = UUID.randomUUID();
            for (int i = 0; i < 6; i++) all.add(jobs.enqueue(queue, prefix + "-" + account + "-" + i, id, payload(2)));
        }
        for (int i = 0; i < 4; i++) all.add(jobs.enqueue(queue, prefix + "-system-" + i, null, payload(2)));

        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                JobExecutor executor = i % 2 == 0 ? a : b;
                futures.add(pool.submit(() -> {
                    long limit = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                    while (System.nanoTime() < limit && jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue=:q AND dedupe_key LIKE :p AND state IN ('QUEUED','RUNNING')")
                            .param("q", queue).param("p", prefix + "%").query(Long.class).single() > 0) {
                        if (!executor.runOne(queue)) Thread.sleep(5);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get(120, TimeUnit.SECONDS);
        }

        assertThat(violations).as("two jobs of one account ran at the same moment").hasValue(0);
        assertThat(peak.get()).as("different accounts did run in parallel").isGreaterThanOrEqualTo(2);
        for (UUID job : all) {
            assertThat(state(job)).isEqualTo("SUCCEEDED");
            assertThat(effects(jdbc, job)).containsExactly(0, 1);
        }
    }

    // ------------------------------------------------------------------ retention and metrics

    @Test
    void retentionDeletesOnlyOldFinishedJobsInBoundedBatches() {
        String queue = queue("retention");
        JobExecutor executor = executor(new ScriptedHandler(queue, job -> Slice.done(0)));
        UUID finishedRecently = enqueue(queue, "recent", null, 1);
        executor.runOne(queue);
        UUID finishedLongAgo = enqueue(queue, "old", null, 1);
        executor.runOne(queue);
        UUID queuedLongAgo = enqueue(queue, "queued", null, 1);
        jdbc.sql("UPDATE app_learning.learning_job SET created_at = clock_timestamp() - interval '30 days' WHERE job_id=:id").param("id", queuedLongAgo).update();
        jdbc.sql("UPDATE app_learning.learning_job SET finished_at = clock_timestamp() - interval '8 days' WHERE job_id=:id").param("id", finishedLongAgo).update();
        // more old rows than one batch holds (500)
        jdbc.sql("""
                INSERT INTO app_learning.learning_job(queue,dedupe_key,payload,state,max_attempts,run_after,finished_at)
                SELECT :q, 'bulk-' || n, '{}'::jsonb, CASE WHEN n % 3 = 0 THEN 'FAILED' WHEN n % 3 = 1 THEN 'CANCELLED' ELSE 'SUCCEEDED' END, 1,
                       clock_timestamp(), clock_timestamp() - interval '9 days' FROM generate_series(1, 1200) n
                """).param("q", queue).update();

        assertThat(executor.purge(false)).isEqualTo(1201);

        assertThat(jobs.status(finishedLongAgo)).isEmpty();
        assertThat(jobs.status(finishedRecently)).isPresent();
        assertThat(jobs.status(queuedLongAgo)).isPresent();
        assertThat(executor.purge(false)).as("at most once a minute").isZero();
        assertThat(executor.purge(true)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE queue=:q").param("q", queue).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void theQueueGaugesCountDueJobsOnlyAndReportTheAgeOfTheOldest() {
        String queue = queue("gauges");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        executor(meters, new ScriptedHandler(queue, job -> Slice.done(0)));
        UUID oldest = enqueue(queue, "a", null, 1);
        enqueue(queue, "b", null, 1);
        UUID later = enqueue(queue, "c", null, 1);
        jdbc.sql("UPDATE app_learning.learning_job SET run_after = clock_timestamp() - interval '90 seconds' WHERE job_id=:id").param("id", oldest).update();
        jdbc.sql("UPDATE app_learning.learning_job SET run_after = clock_timestamp() + interval '1 hour' WHERE job_id=:id").param("id", later).update();

        assertThat(meters.get("mnema_learning_jobs_queue_depth").tag("queue", queue).gauge().value()).as("the job due in an hour is not waiting").isEqualTo(2.0);
        assertThat(meters.get("mnema_learning_jobs_oldest_age_seconds").tag("queue", queue).gauge().value()).isBetween(89.0, 120.0);
        assertThat(repository.depth(queue)).isEqualTo(2);
        assertThat(repository.oldestAgeSeconds(queue("empty"))).isZero();
    }

    @Test
    void twoHandlersForOneQueueStopTheStart() {
        String queue = queue("dup");

        assertThatThrownBy(() -> executor(new ScriptedHandler(queue, job -> Slice.done(0)), new ScriptedHandler(queue, job -> Slice.done(0))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(queue);
        assertThatThrownBy(() -> executor(new ScriptedHandler("Not A Queue", job -> Slice.done(0)))).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ review round: in-flight slices, hooks, give-back, retry, caps

    @Test
    void aSliceInFlightIsNotRecoveredAndACancelWaitsAtMostTheLockTimeout() throws Exception {
        String queue = queue("inflight");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedHandler handler = new ScriptedHandler(queue, job -> {
            Slice result = JobTestSupport.steps(jdbc).apply(job);
            entered.countDown();
            try {
                assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return result;
        });
        JobExecutor executor = executor(handler);
        UUID job = enqueue(queue, "k", null, 1);
        Claimed claimed = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();
        expireLease(job);
        Thread worker = Thread.ofVirtual().start(() -> executor.runClaimed(claimed));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.recoverExpired()).as("the lease is overdue but the row is locked by the slice in flight").isZero();
        assertThatThrownBy(() -> jobs.cancel(job)).as("2 s lock timeout, nothing changed").isInstanceOf(CannotAcquireLockException.class);
        release.countDown();
        worker.join(30_000);

        JobStatus status = jobs.status(job).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(status.cancelRequested()).isFalse();
        assertThat(effects(jdbc, job)).containsExactly(0);
        assertThat(handler.calls).hasValue(1);
    }

    @Test
    void allQueuesTogetherRunAtMostMaxConcurrencySlicesAtOnce() throws Exception {
        String queue = "gcap";
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        JobExecutor executor = executor(new ScriptedHandler(queue, job -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return Slice.done(0);
        }));
        String prefix = UUID.randomUUID().toString();
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) all.add(jobs.enqueue(queue, prefix + i, null, payload(1)));

        executor.drain();
        awaitCondition(() -> inFlight.get() == 2);
        Thread.sleep(400);

        assertThat(inFlight).as("the queue allows 8, the process 2").hasValue(2);
        release.countDown();
        awaitCondition(() -> all.stream().allMatch(id -> state(id).equals("SUCCEEDED")));
        assertThat(peak).hasValue(2);
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long limit = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void aFailureBeforeTheHandlerRanGivesTheJobBackWithoutSpendingAnAttempt() {
        String queue = queue("giveback");
        AtomicInteger failures = new AtomicInteger(1);
        PlatformTransactionManager unreliable = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                if (failures.getAndDecrement() > 0) throw new CannotCreateTransactionException("no connection from the pool");
                return transactions.getTransaction(definition);
            }

            @Override
            public void commit(TransactionStatus status) {
                transactions.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                transactions.rollback(status);
            }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScriptedHandler handler = new ScriptedHandler(queue, JobTestSupport.steps(jdbc));
        JobExecutor executor = executor(meters, unreliable, handler);
        UUID job = enqueue(queue, "k", null, 1);

        assertThat(executor.runOne(queue)).isTrue();

        JobStatus given = jobs.status(job).orElseThrow();
        assertThat(given.state()).isEqualTo(JobState.QUEUED);
        assertThat(given.attempts()).isZero();
        assertThat(given.lastError()).isNull();
        assertThat(handler.calls).hasValue(0);
        assertThat(backoffSeconds(job)).as("one backoff step, so a sick pool is not hammered").isBetween(9.9, 10.1);
        assertThat(meters.get("mnema_learning_jobs_total").tag("outcome", "released").counter().count()).isEqualTo(1.0);
        makeDue(job);
        assertThat(executor.runOne(queue)).isTrue();
        assertThat(state(job)).isEqualTo("SUCCEEDED");
    }

    @Test
    void aSliceThatCommitsProgressForgivesEarlierFailures() {
        String queue = "consec";
        AtomicInteger calls = new AtomicInteger();
        JobExecutor executor = executor(new ScriptedHandler(queue, job -> {
            int call = calls.incrementAndGet();
            if (call == 1 || call == 3) throw new RetryableJobException("flaky");
            return JobTestSupport.steps(jdbc).apply(job);
        }));
        UUID job = jobs.enqueue(queue, "k-" + UUID.randomUUID(), null, payload(3));

        executor.runOne(queue);
        assertThat(jobs.status(job).orElseThrow().attempts()).isEqualTo(1);
        makeDue(job);
        executor.runOne(queue);

        JobStatus afterProgress = jobs.status(job).orElseThrow();
        assertThat(afterProgress.state()).as("max-attempts is 2: without the reset the second failure would have ended the job").isEqualTo(JobState.QUEUED);
        assertThat(afterProgress.attempts()).isEqualTo(1);
        assertThat(afterProgress.progressCount()).isEqualTo(1);
        makeDue(job);
        executor.runOne(queue);
        JobStatus done = jobs.status(job).orElseThrow();
        assertThat(done.state()).isEqualTo(JobState.SUCCEEDED);
        assertThat(done.attempts()).isZero();
        assertThat(effects(jdbc, job)).containsExactly(0, 1, 2);
    }

    private record Ended(UUID job, JobState state, long progressCount, int next) {
    }

    private ScriptedHandler hooked(String queue, java.util.function.Function<Job, Slice> body, List<Ended> ended, boolean throwing) {
        return new ScriptedHandler(queue, body, (job, state) -> {
            ended.add(new Ended(job.id(), state, job.progressCount(), job.progress() == null ? -1 : job.progress().path("next").intValue(-1)));
            jdbc.sql("INSERT INTO app_learning.job_test_effect(job_id, step) VALUES (:job, 99)").param("job", job.id()).update();
            if (throwing) throw new IllegalStateException("the compensation failed");
        });
    }

    @Test
    void onEndedRunsInTheTransactionThatSettlesAFailedJobAndNotForSuccessOrRetry() {
        String queue = queue("ended");
        List<Ended> ended = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JobExecutor executor = executor(meters, hooked(queue, job -> {
            if (job.payload().path("steps").intValue(0) == 7) return Slice.done(0);
            if (calls.incrementAndGet() == 1) return JobTestSupport.steps(jdbc).apply(job);
            throw job.payload().path("steps").intValue(0) == 5 ? new PermanentJobException("source_gone") : new RetryableJobException("again");
        }, ended, false));
        UUID succeeded = enqueue(queue, "ok", null, 7);
        UUID permanent = enqueue(queue, "permanent", null, 5);
        UUID retried = enqueue(queue, "retried", null, 6);

        executor.runOne(queue);
        executor.runOne(queue);
        executor.runOne(queue);

        assertThat(state(succeeded)).isEqualTo("SUCCEEDED");
        assertThat(state(permanent)).isEqualTo("FAILED");
        assertThat(state(retried)).isEqualTo("QUEUED");
        assertThat(ended).as("the first job ran its slice (steps=5: the first call), then failed permanently").hasSize(1);
        assertThat(ended.get(0)).isEqualTo(new Ended(permanent, JobState.FAILED, 1, 1));
        assertThat(effects(jdbc, permanent)).as("the hook's write committed with the settlement").contains(99);
        assertThat(effects(jdbc, retried)).isEmpty();
    }

    @Test
    void onEndedRunsWhenTheLastAttemptFailsWhenALeaseIsLostForTheLastTimeAndWhenAJobIsCancelled() {
        String queue = queue("ended-more");
        List<Ended> ended = new CopyOnWriteArrayList<>();
        JobExecutor executor = executor(hooked(queue, job -> {
            throw new RetryableJobException("again");
        }, ended, false));
        UUID exhausted = enqueue(queue, "exhausted", null, 1);
        UUID crashed = enqueue(queue, "crashed", null, 1);
        UUID cancelled = enqueue(queue, "cancelled", null, 1);
        jdbc.sql("UPDATE app_learning.learning_job SET max_attempts=1 WHERE queue=:q").param("q", queue).update();

        executor.runOne(queue);
        Claimed second = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();
        expireLease(second.id());
        assertThat(executor.recoverExpired()).isEqualTo(1);
        Claimed third = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();
        jobs.cancel(third.id());
        executor.runClaimed(third);

        assertThat(ended).extracting(Ended::job, Ended::state).containsExactly(org.assertj.core.groups.Tuple.tuple(exhausted, JobState.FAILED),
                org.assertj.core.groups.Tuple.tuple(crashed, JobState.FAILED), org.assertj.core.groups.Tuple.tuple(cancelled, JobState.CANCELLED));
        for (UUID job : List.of(exhausted, crashed, cancelled)) assertThat(effects(jdbc, job)).containsExactly(99);
    }

    @Test
    void aHookThatThrowsCannotKeepAJobFromEndingAndLosesOnlyItsOwnWrites() {
        String queue = queue("ended-broken");
        List<Ended> ended = new CopyOnWriteArrayList<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JobExecutor executor = executor(meters, hooked(queue, job -> {
            throw new PermanentJobException("source_gone");
        }, ended, true));
        UUID failed = enqueue(queue, "failed", null, 1);
        UUID cancelled = enqueue(queue, "cancelled", null, 1);

        executor.runOne(queue);
        Claimed claimed = repository.claim(queue, UUID.randomUUID(), Duration.ofSeconds(60)).orElseThrow();
        jobs.cancel(claimed.id());
        executor.runClaimed(claimed);

        assertThat(state(failed)).isEqualTo("FAILED");
        assertThat(state(cancelled)).isEqualTo("CANCELLED");
        assertThat(ended).hasSize(2);
        assertThat(effects(jdbc, failed)).as("rolled back with the failed hook").isEmpty();
        assertThat(effects(jdbc, cancelled)).isEmpty();
        assertThat(meters.get("mnema_learning_jobs_total").tag("outcome", "hook_failed").counter().count()).isEqualTo(2.0);
    }

    @Test
    void aFailedOrCancelledJobIsRequeuedByRetryUnderItsOwnKeyAndAKeyIsFreeOnlyAfterRetention() {
        String queue = queue("retry-api");
        AtomicInteger calls = new AtomicInteger();
        JobExecutor executor = executor(new ScriptedHandler(queue, job -> {
            if (calls.incrementAndGet() == 1) throw new PermanentJobException("source_gone");
            return JobTestSupport.steps(jdbc).apply(job);
        }));
        UUID job = enqueue(queue, "k", null, 2);
        executor.runOne(queue);
        assertThat(state(job)).isEqualTo("FAILED");
        assertThat(enqueue(queue, "k", null, 2)).as("enqueueing the key again does not replace a failed job").isEqualTo(job);

        assertThat(jobs.retry(job)).isTrue();

        JobStatus requeued = jobs.status(job).orElseThrow();
        assertThat(requeued.state()).isEqualTo(JobState.QUEUED);
        assertThat(requeued.attempts()).isZero();
        assertThat(requeued.lastError()).isNull();
        assertThat(requeued.finishedAt()).isNull();
        assertThat(jobs.retry(job)).as("queued: nothing to retry").isFalse();
        assertThat(executor.runOne(queue)).isTrue();
        assertThat(state(job)).isEqualTo("SUCCEEDED");
        assertThat(jobs.retry(job)).as("succeeded: nothing to retry").isFalse();
        assertThat(jobs.retry(UUID.randomUUID())).isFalse();

        UUID other = enqueue(queue, "other", null, 1);
        jobs.cancel(other);
        assertThat(jobs.retry(other)).isTrue();
        assertThat(jobs.status(other).orElseThrow().cancelRequested()).isFalse();

        jdbc.sql("UPDATE app_learning.learning_job SET finished_at = clock_timestamp() - interval '8 days' WHERE job_id=:id").param("id", job).update();
        executor.purge(true);
        assertThat(enqueue(queue, "k", null, 2)).as("past the retention the key is a new job").isNotEqualTo(job);
    }

    @Test
    void aGaugeThatCannotBeReadReportsNaNNotAFalseZero() {
        JobRepository broken = mock(JobRepository.class);
        when(broken.depth(anyString())).thenThrow(new IllegalStateException("database down"));
        when(broken.oldestAgeSeconds(anyString())).thenThrow(new IllegalStateException("database down"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("handler", new ScriptedHandler("nan-queue", job -> Slice.done(0)));

        executors.add(new JobExecutor(broken, settings, beans.getBeanProvider(JobHandler.class), meters, transactions));

        assertThat(meters.get("mnema_learning_jobs_queue_depth").tag("queue", "nan-queue").gauge().value()).isNaN();
        assertThat(meters.get("mnema_learning_jobs_oldest_age_seconds").tag("queue", "nan-queue").gauge().value()).isNaN();
    }

    @Test
    void theRetentionLeaseAndDueConditionsAreAnsweredByTheirIndexes() {
        List<String> plans = new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            List<String> found = new ArrayList<>();
            for (String sql : List.of(
                    "SELECT job_id FROM app_learning.learning_job WHERE state IN ('SUCCEEDED','FAILED','CANCELLED') AND finished_at < now() - (604800000 * interval '1 millisecond') ORDER BY finished_at, job_id LIMIT 500",
                    "SELECT job_id FROM app_learning.learning_job WHERE queue='q' AND state='RUNNING' AND lease_until < now() ORDER BY lease_until, job_id LIMIT 1",
                    "SELECT job_id FROM app_learning.learning_job WHERE queue='q' AND state='QUEUED' AND run_after <= now() ORDER BY run_after, job_id LIMIT 1")) {
                found.add(String.join("\n", jdbc.sql("EXPLAIN " + sql).query(String.class).list()));
            }
            return found;
        });

        assertThat(plans.get(0)).contains("learning_job_finished").containsPattern("Index Cond: .*finished_at <");
        assertThat(plans.get(1)).contains("learning_job_lease").containsPattern("Index Cond: .*lease_until <");
        assertThat(plans.get(2)).contains("learning_job_due").containsPattern("Index Cond: .*run_after <=");
    }
}
