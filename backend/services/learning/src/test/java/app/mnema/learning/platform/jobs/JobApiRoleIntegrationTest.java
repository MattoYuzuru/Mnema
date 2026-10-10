package app.mnema.learning.platform.jobs;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static app.mnema.learning.platform.jobs.JobTestSupport.payload;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code learning.runtime.roles=api}: the process enqueues, reads and cancels jobs but has no executor, so even a handler bean in it never runs. */
@SpringBootTest(properties = {"learning.runtime.roles=api", "learning.runtime.provider-credentials=worker", "learning.ai.provider=",
        "learning.jobs.sweep-interval=PT0.1S", "spring.datasource.hikari.maximum-pool-size=4"})
class JobApiRoleIntegrationTest extends PostgresIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        ScriptedHandler apiHandler() {
            return new ScriptedHandler("api-queue", job -> Slice.done(0));
        }
    }

    @Autowired private ApplicationContext context;
    @Autowired private LearningJobs jobs;
    @Autowired private JdbcClient jdbc;
    @Autowired private ScriptedHandler handler;

    @Test
    void anApiProcessHasNoExecutorAndItsJobsWaitForAWorker() throws Exception {
        assertThat(context.getBeanNamesForType(JobExecutor.class)).isEmpty();

        UUID job = jobs.enqueue("api-queue", "k-" + UUID.randomUUID(), null, payload(1));
        Thread.sleep(600);

        assertThat(jobs.status(job).orElseThrow().state()).isEqualTo(JobState.QUEUED);
        assertThat(handler.calls).hasValue(0);
        assertThat(jobs.cancel(job)).contains(JobState.CANCELLED);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.learning_job WHERE job_id=:id").param("id", job).query(Long.class).single()).isEqualTo(1L);
    }
}
