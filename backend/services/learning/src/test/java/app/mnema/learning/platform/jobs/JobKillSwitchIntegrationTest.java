package app.mnema.learning.platform.jobs;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.UUID;

import static app.mnema.learning.platform.jobs.JobTestSupport.payload;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code learning.jobs.enabled=false}: the executor exists but claims nothing, neither from the wake-up of an enqueue nor from its sweep (every 0.1 s
 * here); jobs are still accepted and wait. {@link JobWorkerIntegrationTest} is the same wiring with the switch on.
 */
@SpringBootTest(properties = {"learning.runtime.roles=all", "learning.jobs.enabled=false", "learning.jobs.sweep-interval=PT0.1S", "spring.datasource.hikari.maximum-pool-size=4"})
class JobKillSwitchIntegrationTest extends PostgresIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        ScriptedHandler killedHandler() {
            return new ScriptedHandler("killed", job -> Slice.done(0));
        }
    }

    @Autowired private LearningJobs jobs;
    @Autowired private ScriptedHandler handler;
    @Autowired private JobExecutor executor;

    @Test
    void nothingIsClaimedWhileTheGlobalSwitchIsOff() throws Exception {
        UUID job = jobs.enqueue("killed", "k-" + UUID.randomUUID(), null, payload(1));
        executor.wake();
        Thread.sleep(1_200);

        JobStatus status = jobs.status(job).orElseThrow();
        assertThat(status.state()).isEqualTo(JobState.QUEUED);
        assertThat(status.attempts()).isZero();
        assertThat(handler.calls).hasValue(0);
    }
}
