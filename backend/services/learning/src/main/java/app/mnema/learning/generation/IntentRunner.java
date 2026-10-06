package app.mnema.learning.generation;

import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.wake.WakeTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/** Worker-only parsing of intent; the unchanged HTTP request waits for its ephemeral result without holding a connection. */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
final class IntentRunner implements WakeTarget, DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(IntentRunner.class);
    private final IntentQueue queue;
    private final IntentService service;
    private final Semaphore slots;
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    IntentRunner(IntentQueue queue, IntentService service, AiProperties ai) {
        this.queue = queue;
        this.service = service;
        this.slots = new Semaphore(ai.permits().text());
    }

    @Override
    public String channel() { return "mnema_generation_intents"; }

    @Override
    public void wake() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            threads.execute(() -> {
                try {
                    sweep();
                } finally {
                    draining.set(false);
                }
            });
        } catch (RejectedExecutionException closing) {
            draining.set(false);
            // The deadline and the next worker sweep recover the request.
        }
    }

    @Scheduled(initialDelayString = "${learning.generation.worker.sweep-interval:PT2S}", fixedDelayString = "${learning.generation.worker.sweep-interval:PT2S}")
    void sweep() {
        try {
            queue.expire();
            while (slots.tryAcquire()) {
                java.util.Optional<IntentQueue.Job> job;
                try {
                    job = queue.claim();
                } catch (RuntimeException failure) {
                    slots.release();
                    throw failure;
                }
                if (job.isEmpty()) {
                    slots.release();
                    break;
                }
                try {
                    threads.execute(() -> run(job.orElseThrow()));
                } catch (RejectedExecutionException closing) {
                    slots.release();
                    return;
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("generation_intent_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    private void run(IntentQueue.Job job) {
        try {
            queue.complete(job.id(), service.execute(job), null);
        } catch (CapabilityUnavailableException unavailable) {
            String reason = (String) unavailable.extension().members().get("reason");
            queue.complete(job.id(), null, "PROVIDER_NOT_CONFIGURED".equals(reason) ? reason : "TEMPORARILY_UNAVAILABLE");
        } catch (RuntimeException failure) {
            LOG.warn("generation_intent_failed error_type={}", failure.getClass().getSimpleName());
            queue.complete(job.id(), null, "TEMPORARILY_UNAVAILABLE");
        } finally {
            slots.release();
            wake();
        }
    }

    @Override
    public void destroy() { threads.shutdownNow(); }
}
