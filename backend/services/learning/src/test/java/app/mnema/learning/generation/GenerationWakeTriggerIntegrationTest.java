package app.mnema.learning.generation;

import app.mnema.learning.platform.wake.PostgresWakeListener;
import app.mnema.learning.platform.wake.WakeTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step trigger of {@code V40}: a session that creates its steps notifies {@code mnema_generation_steps} when its transaction commits, so a
 * {@code worker} process in another JVM can take them without waiting for its sweep. The dispatcher of this context is the in-process half; the listener
 * here stands in for the other process.
 */
class GenerationWakeTriggerIntegrationTest extends GenerationIntegrationTest {
    @Value("${spring.datasource.url}") private String url;

    @Test
    void creatingASessionNotifiesTheStepChannelOfOtherProcesses() throws Exception {
        AtomicInteger wakes = new AtomicInteger();
        PostgresWakeListener listener = new PostgresWakeListener(List.of(new WakeTarget() {
            @Override public String channel() { return "mnema_generation_steps"; }

            @Override public void wake() { wakes.incrementAndGet(); }
        }), url, username(), password());
        listener.start();
        try {
            await("the listener to connect", Duration.ofSeconds(15), listener::isListening);
            Thread.sleep(500);
            int before = wakes.get();
            UUID owner = UUID.randomUUID();
            start(owner, deck(owner), spec("глаголы движения"));
            await("the notification of the new step", Duration.ofSeconds(15), () -> wakes.get() > before);
        } finally {
            listener.stop();
        }
    }
}
