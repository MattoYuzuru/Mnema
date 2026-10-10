package app.mnema.learning.platform.jobs;

import app.mnema.learning.platform.wake.PostgresWakeListener;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static app.mnema.learning.platform.jobs.JobTestSupport.effects;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code learning.runtime.roles=worker}: the process has the executor and the listener, and a job that another process inserts is run from the {@code NOTIFY}
 * of its insert alone, with the sweep set to an hour. Jobs run on virtual threads, a slice at a time, and never two of one account at once.
 */
@SpringBootTest(properties = {"learning.runtime.roles=worker", "learning.jobs.sweep-interval=PT5M", "learning.jobs.auto.concurrency=4",
        "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
        "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S", "spring.datasource.hikari.maximum-pool-size=8"})
class JobWorkerIntegrationTest extends PostgresIntegrationTest {
    static final ConcurrentHashMap<UUID, AtomicInteger> RUNNING = new ConcurrentHashMap<>();
    static final AtomicInteger VIOLATIONS = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        JobHandler autoHandler(JdbcClient jdbc) {
            return new ScriptedHandler("auto", job -> {
                int concurrent = RUNNING.computeIfAbsent(job.accountId(), key -> new AtomicInteger()).incrementAndGet();
                try {
                    if (concurrent > 1) VIOLATIONS.incrementAndGet();
                    Thread.sleep(15);
                    return JobTestSupport.steps(jdbc).apply(job);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RetryableJobException("interrupted");
                } finally {
                    RUNNING.get(job.accountId()).decrementAndGet();
                }
            });
        }
    }

    @Autowired private ApplicationContext context;
    @Autowired private PostgresWakeListener listener;
    @Autowired private JdbcClient jdbc;

    @BeforeAll
    static void effectTable(@Autowired JdbcClient jdbc) {
        JobTestSupport.createEffectTable(jdbc);
    }

    @Test
    void aWorkerProcessRunsJobsInsertedByAnotherProcessFromTheirNotification() throws Exception {
        assertThat(context.getBeanNamesForType(JobExecutor.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(LearningJobs.class)).hasSize(1);
        long limit = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!listener.isListening() && System.nanoTime() < limit) Thread.sleep(25);
        assertThat(listener.isListening()).isTrue();
        Thread.sleep(500);

        List<UUID> inserted = new ArrayList<>();
        for (UUID account : List.of(UUID.randomUUID(), UUID.randomUUID())) {
            for (int i = 0; i < 3; i++) {
                // a plain insert, as a process without the executor would make it: nothing but the trigger's NOTIFY can wake this worker
                inserted.add(jdbc.sql("INSERT INTO app_learning.learning_job(queue,dedupe_key,account_id,payload,state,max_attempts,run_after) "
                                + "VALUES ('auto',:key,:account,'{\"steps\":3}','QUEUED',3,clock_timestamp()) RETURNING job_id")
                        .param("key", UUID.randomUUID().toString()).param("account", account).query(UUID.class).single());
            }
        }

        limit = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        long open = inserted.size();
        while (open > 0 && System.nanoTime() < limit) {
            Thread.sleep(50);
            open = jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE job_id = ANY(:ids) AND state <> 'SUCCEEDED'")
                    .param("ids", inserted.toArray(UUID[]::new)).query(Long.class).single();
        }

        assertThat(open).as("jobs the notification woke the worker for").isZero();
        assertThat(VIOLATIONS).as("two jobs of one account ran at once").hasValue(0);
        for (UUID job : inserted) assertThat(effects(jdbc, job)).containsExactly(0, 1, 2);
    }
}
