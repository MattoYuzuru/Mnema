package app.mnema.learning.speech;

import app.mnema.learning.platform.wake.PostgresWakeListener;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code learning.runtime.roles=worker}: the process has the workers and the listener, and an input that another process admits is taken at once from the
 * {@code NOTIFY} of its insert, with every sweep set to minutes so that nothing but the notification can have woken the worker. (With no consent
 * the worker fails the input {@code UNAVAILABLE}: that it left QUEUED within seconds is the proof.)
 */
@SpringBootTest(properties = {"learning.runtime.roles=worker", "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true",
        "learning.speech.sweep-interval=PT5M", "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S",
        "spring.datasource.hikari.maximum-pool-size=4"})
class SpeechWorkerWakeIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private PostgresWakeListener listener;
    @Autowired private JdbcClient jdbc;
    @Autowired private SpeechInputRepository repository;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aWorkerProcessListensAndTakesAnInputFromTheNotificationOfItsInsert() throws Exception {
        assertThat(context.getBeanNamesForType(SpeechInputWorker.class)).hasSize(1);
        assertThat(context.containsBean("assessmentRunner")).isTrue();
        long limit = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!listener.isListening() && System.nanoTime() < limit) Thread.sleep(25);
        assertThat(listener.isListening()).isTrue();
        Thread.sleep(500);

        UUID owner = UUID.randomUUID();
        UUID input = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> repository.insert(input, owner, UUID.randomUUID(), new byte[32],
                app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", 1_000, "clip".getBytes(StandardCharsets.UTF_8), null,
                app.mnema.learning.ai.Transcription.Region.RU, Duration.ofSeconds(30), Duration.ofMinutes(15)));

        String state = "QUEUED";
        limit = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (state.equals("QUEUED") && System.nanoTime() < limit) {
            Thread.sleep(50);
            state = jdbc.sql("SELECT state FROM app_learning.speech_input WHERE speech_input_id=:id").param("id", input).query(String.class).single();
        }
        assertThat(state).as("claimed and settled by the worker the notification woke").isNotEqualTo("QUEUED");
    }
}
