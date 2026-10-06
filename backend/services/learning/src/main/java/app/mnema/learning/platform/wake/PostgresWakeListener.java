package app.mnema.learning.platform.wake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The wake-up of a split topology (architecture section 4): a {@code worker} process (never {@code all}, which wakes itself in process) holds one dedicated
 * JDBC connection that {@code LISTEN}s on the channel of every {@link WakeTarget}, on its own virtual thread, and wakes the target whose channel
 * was notified. The api process needs nothing: the triggers of {@code V39} notify when a transaction commits work.
 *
 * <p>The connection is never one of the pool's (a listening connection must stay open and must not be handed to a request). It reconnects with a
 * backoff of 0.5 s doubling to 30 s, and wakes every target after each (re)connect because notifications sent while it was away are gone. It also
 * probes the connection with {@code SELECT 1} on every idle poll, so a dead socket is noticed within seconds. The sweepers remain the source of truth:
 * nothing depends on a notification arriving.
 *
 * <p>The PostgreSQL driver is a runtime-only dependency, so its notification API is reached by reflection ({@code PGConnection#getNotifications(int)}
 * and {@code PGNotification#getName()}); the shape is stable and covered by an integration test against a real server.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker'")
public final class PostgresWakeListener implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresWakeListener.class);
    private static final int POLL_MS = 10_000;
    private static final long BACKOFF_START_MS = 500;
    private static final long BACKOFF_CAP_MS = 30_000;

    private final Map<String, WakeTarget> targets;
    private final String url;
    private final String user;
    private final String password;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Connection connection;
    private volatile boolean listening;

    public PostgresWakeListener(List<WakeTarget> targets, @Value("${spring.datasource.url}") String url,
                         @Value("${spring.datasource.username:}") String user, @Value("${spring.datasource.password:}") String password) {
        this.targets = targets.stream().collect(Collectors.toUnmodifiableMap(WakeTarget::channel, Function.identity()));
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        thread = Thread.ofVirtual().name("wake-listener").start(this::loop);
    }

    @Override
    public synchronized void stop() {
        running = false;
        closeQuietly(connection);
        Thread loop = thread;
        if (loop != null) loop.interrupt();
    }

    @Override
    public boolean isRunning() { return running; }

    /** Whether the dedicated connection is open and listening right now (false while reconnecting): what a readiness check or a test waits for. */
    public boolean isListening() { return listening; }

    private void loop() {
        long backoff = BACKOFF_START_MS;
        while (running) {
            try (Connection opened = DriverManager.getConnection(url, user, password)) {
                connection = opened;
                Class<?> pg = Class.forName("org.postgresql.PGConnection");
                Object pgConnection = opened.unwrap(pg);
                Method poll = pg.getMethod("getNotifications", int.class);
                try (Statement listen = opened.createStatement()) {
                    for (String channel : targets.keySet()) listen.execute("LISTEN " + channel);
                }
                LOG.info("wake_listener_connected channels={}", targets.size());
                backoff = BACKOFF_START_MS;
                // whatever was notified while this connection was away is gone: look once at every queue
                targets.values().forEach(WakeTarget::wake);
                listening = true;
                while (running) {
                    Object[] notifications = (Object[]) poll.invoke(pgConnection, POLL_MS);
                    if (notifications != null) {
                        for (Object notification : notifications) {
                            WakeTarget target = targets.get((String) notification.getClass().getMethod("getName").invoke(notification));
                            if (target != null) target.wake();
                        }
                    }
                    try (Statement probe = opened.createStatement()) {
                        probe.execute("SELECT 1");
                    }
                }
            } catch (SQLException | ReflectiveOperationException | RuntimeException failure) {
                if (!running) return;
                Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invoked && invoked.getCause() != null ? invoked.getCause() : failure;
                LOG.warn("wake_listener_disconnected error_type={} retry_ms={}", cause.getClass().getSimpleName(), backoff);
            } finally {
                listening = false;
                connection = null;
            }
            if (!running) return;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(BACKOFF_CAP_MS, backoff * 2);
        }
    }

    private static void closeQuietly(Connection toClose) {
        if (toClose == null) return;
        try {
            toClose.close();
        } catch (SQLException ignored) {
            // closing only breaks the blocking read
        }
    }
}
