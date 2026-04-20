package app.mnema.ai.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AiJobCancellationRegistry {

    private final ConcurrentMap<UUID, Thread> activeThreads = new ConcurrentHashMap<>();

    public Registration register(UUID jobId) {
        if (jobId == null) {
            return () -> {
            };
        }
        Thread thread = Thread.currentThread();
        activeThreads.put(jobId, thread);
        return () -> activeThreads.remove(jobId, thread);
    }

    public boolean cancel(UUID jobId) {
        if (jobId == null) {
            return false;
        }
        Thread thread = activeThreads.get(jobId);
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
