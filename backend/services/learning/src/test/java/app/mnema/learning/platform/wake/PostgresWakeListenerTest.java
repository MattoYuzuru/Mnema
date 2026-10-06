package app.mnema.learning.platform.wake;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The listener of a worker process against a real PostgreSQL: a notification wakes its target only, and a lost connection is replaced. */
class PostgresWakeListenerTest extends PostgresIntegrationTest {
    private static final class Counting implements WakeTarget {
        private final String channel;
        final AtomicInteger wakes = new AtomicInteger();

        Counting(String channel) { this.channel = channel; }

        @Override public String channel() { return channel; }

        @Override public void wake() { wakes.incrementAndGet(); }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long limit = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < limit) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out: " + what);
    }

    private static void sql(String url, String statement) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username(), password()); Statement run = connection.createStatement()) {
            run.execute(statement);
        }
    }

    @Test
    void aNotificationWakesTheTargetOfItsChannelOnlyAndConnectingWakesEveryTarget() throws Exception {
        String url = createDatabase("wake_channels");
        Counting steps = new Counting("mnema_generation_steps");
        Counting speech = new Counting("mnema_speech_inputs");
        PostgresWakeListener listener = new PostgresWakeListener(List.of(steps, speech), url, username(), password());
        listener.start();
        try {
            await("the listener to connect", listener::isListening);
            assertThat(steps.wakes.get()).as("a look at every queue after connecting").isEqualTo(1);
            assertThat(speech.wakes.get()).isEqualTo(1);

            sql(url, "SELECT pg_notify('mnema_speech_inputs', '')");
            await("the speech target to wake", () -> speech.wakes.get() == 2);
            assertThat(steps.wakes.get()).isEqualTo(1);

            sql(url, "SELECT pg_notify('mnema_generation_steps', '')");
            await("the step target to wake", () -> steps.wakes.get() == 2);
            sql(url, "SELECT pg_notify('mnema_unknown', '')");
        } finally {
            listener.stop();
        }
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void aConnectionTheServerEndedIsReplacedAndEveryTargetLooksAgain() throws Exception {
        String url = createDatabase("wake_reconnect");
        Counting steps = new Counting("mnema_generation_steps");
        String probeUrl = url + (url.contains("?") ? "&" : "?") + "ApplicationName=wake-listener-test";
        PostgresWakeListener listener = new PostgresWakeListener(List.of(steps), probeUrl, username(), password());
        listener.start();
        try {
            await("the listener to connect", listener::isListening);
            assertThat(steps.wakes.get()).isEqualTo(1);

            sql(url, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='wake-listener-test'");
            await("the listener to notice and reconnect", () -> steps.wakes.get() == 2 && listener.isListening());

            sql(url, "SELECT pg_notify('mnema_generation_steps', '')");
            await("a notification on the new connection", () -> steps.wakes.get() == 3);
            try (Connection connection = DriverManager.getConnection(url, username(), password()); Statement run = connection.createStatement();
                 ResultSet channels = run.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='wake-listener-test'")) {
                channels.next();
                assertThat(channels.getInt(1)).as("one listening connection, not one per attempt").isEqualTo(1);
            }
        } finally {
            listener.stop();
        }
    }
}
