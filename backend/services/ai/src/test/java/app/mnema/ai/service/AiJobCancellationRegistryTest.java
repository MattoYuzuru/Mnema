package app.mnema.ai.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AiJobCancellationRegistryTest {

    @Test
    void cancelInterruptsRegisteredThread() throws Exception {
        AiJobCancellationRegistry registry = new AiJobCancellationRegistry();
        UUID jobId = UUID.randomUUID();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Thread worker = Thread.ofVirtual().start(() -> {
            try (AiJobCancellationRegistry.Registration ignored = registry.register(jobId)) {
                registered.countDown();
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            } finally {
                finished.countDown();
            }
        });

        assertThat(registered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.cancel(jobId)).isTrue();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        worker.join(TimeUnit.SECONDS.toMillis(1));
    }
}
