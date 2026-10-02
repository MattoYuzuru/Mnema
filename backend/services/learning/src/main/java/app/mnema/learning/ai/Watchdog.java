package app.mnema.learning.ai;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

/**
 * Closes a blocking stream when it stays silent for {@code idleNanos} or the overall deadline passes, which is the only
 * way to interrupt a blocked {@code InputStream.read}. Runs on one virtual thread per watched stream.
 */
final class Watchdog implements AutoCloseable {
    private final Closeable target;
    private final long idleNanos;
    private final long deadlineNanos;
    private final Thread thread;
    private volatile long lastActivity = System.nanoTime();
    private volatile boolean stopped;
    private volatile boolean fired;

    Watchdog(Closeable target, long idleNanos, long deadlineNanos) {
        this.target = target;
        this.idleNanos = idleNanos;
        this.deadlineNanos = deadlineNanos;
        this.thread = Thread.ofVirtual().name("ai-watchdog").start(this::watch);
    }

    void touch() { lastActivity = System.nanoTime(); }

    /** True when the watchdog, not the peer, ended the stream. */
    boolean fired() { return fired; }

    private void watch() {
        while (!stopped) {
            long now = System.nanoTime();
            long untilDeadline = deadlineNanos - now;
            long untilIdle = idleNanos - (now - lastActivity);
            if (untilDeadline <= 0 || untilIdle <= 0) {
                fired = true;
                try {
                    target.close();
                } catch (IOException ignored) {
                    // the stream is being abandoned; there is nothing left to report
                }
                return;
            }
            LockSupport.parkNanos(Math.min(untilDeadline, untilIdle));
        }
    }

    @Override
    public void close() {
        stopped = true;
        LockSupport.unpark(thread);
    }
}
