package app.mnema.learning.platform.jobs;

import app.mnema.learning.platform.jobs.JobRepository.Claimed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The deterministic hand of the job executor for tests of a handler that lives in another package: a worker built by hand around the given handlers (the process's
 * own executor is not involved), able to claim a job, run what it claimed slice by slice, let a lease expire and recover it. The package-private executor and
 * repository stay package-private; this is the one public door, in the test tree only.
 */
public final class JobTestAccess implements AutoCloseable {
    private final JobExecutor executor;
    private final JobRepository repository;
    private final JdbcClient jdbc;
    private final Duration lease = Duration.ofSeconds(60);
    private final Map<UUID, Claimed> claims = new HashMap<>();

    private JobTestAccess(JobExecutor executor, JobRepository repository, JdbcClient jdbc) {
        this.executor = executor;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** A worker around {@code handlers}, using the beans of the running application. */
    public static JobTestAccess around(ApplicationContext context, JobHandler... handlers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < handlers.length; i++) beans.addBean("handler" + i, handlers[i]);
        JobRepository repository = context.getBean(JobRepository.class);
        JobExecutor executor = new JobExecutor(repository, context.getBean(JobSettings.class), beans.getBeanProvider(JobHandler.class),
                new SimpleMeterRegistry(), context.getBean(PlatformTransactionManager.class));
        return new JobTestAccess(executor, repository, context.getBean(JdbcClient.class));
    }

    /** Claims the next due job of the queue as a worker would (the per-account limit applies) and remembers the claim. */
    public Optional<UUID> claim(String queue) {
        Optional<Claimed> claimed = repository.claim(queue, UUID.randomUUID(), lease);
        claimed.ifPresent(claim -> claims.put(claim.id(), claim));
        return claimed.map(Claimed::id);
    }

    /** Runs a job this access claimed until it ends or the worker "dies" (an Error thrown by the handler propagates). */
    public void run(UUID job) {
        executor.runClaimed(claims.get(job));
    }

    /** Claims and runs the next due job of the queue to its end; false when nothing was due. */
    public boolean runOne(String queue) {
        return executor.runOne(queue);
    }

    public void expireLease(UUID job) {
        jdbc.sql("UPDATE app_learning.learning_job SET lease_until = clock_timestamp() - interval '1 second' WHERE job_id = :id").param("id", job).update();
    }

    public void makeDue(UUID job) {
        jdbc.sql("UPDATE app_learning.learning_job SET run_after = clock_timestamp() - interval '1 second' WHERE job_id = :id").param("id", job).update();
    }

    public int recoverExpired() {
        return executor.recoverExpired();
    }

    @Override
    public void close() {
        executor.destroy();
    }
}
