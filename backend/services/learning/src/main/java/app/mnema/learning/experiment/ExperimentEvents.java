package app.mnema.learning.experiment;

import app.mnema.learning.platform.api.RateLimitedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts exposures and conversions as {@code mnema_experiment_events_total{key,variant,event}}: a Micrometer counter and nothing else, no
 * database row and no account in a label (cardinality is the experiments times their variants times two). The variant is the server's assignment,
 * never a client claim. An account may send {@value #PER_MINUTE} events a minute per instance; the window is a best-effort guard of one instance, not
 * state, because a counter an account can flood is worthless. The table is bounded ({@value #MAX_WINDOWS} accounts): when it is full, expired windows are
 * swept at most once per {@value #SWEEP_MILLIS} ms (so a full table costs a map lookup per event, not a scan), and an account that still finds no room is
 * refused with a bounded retry until a window frees up. Admission and updates share a short in-memory lock so concurrent new accounts cannot
 * exceed the table bound. Unknown or disabled experiments are ignored before they can consume capacity or create a metric.
 */
@Service
public class ExperimentEvents {
    public enum Event { EXPOSURE, CONVERSION }

    static final int PER_MINUTE = 30;
    private static final long WINDOW_MILLIS = 60_000;
    static final int MAX_WINDOWS = 20_000;
    private static final long SWEEP_MILLIS = 5_000;

    private record Window(long startedAt, int count) { }

    private final ExperimentAssignments assignments;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int maxWindows;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong sweptAt = new AtomicLong();

    @Autowired
    ExperimentEvents(ExperimentAssignments assignments, MeterRegistry meters) {
        this(assignments, meters, Clock.systemUTC());
    }

    ExperimentEvents(ExperimentAssignments assignments, MeterRegistry meters, Clock clock) {
        this(assignments, meters, clock, MAX_WINDOWS);
    }

    ExperimentEvents(ExperimentAssignments assignments, MeterRegistry meters, Clock clock, int maxWindows) {
        this.assignments = assignments;
        this.meters = meters;
        this.clock = clock;
        this.maxWindows = maxWindows;
    }

    int trackedAccounts() {
        return windows.size();
    }

    /**
     * @return whether the event was counted; false for an experiment that is not enabled
     * @throws RateLimitedException the account sent too many events this minute or a new account cannot be tracked within the bounded table
     */
    public boolean record(UUID account, String key, Event event) {
        if (!assignments.known(key)) return false;
        long now = clock.millis();
        synchronized (windows) {
            if (windows.size() >= maxWindows) sweep(now);
            Window old = windows.get(account);
            if (old == null && windows.size() >= maxWindows) throw new RateLimitedException((SWEEP_MILLIS + 999) / 1000);
            Window window = old == null || now - old.startedAt() >= WINDOW_MILLIS ? new Window(now, 1)
                    : new Window(old.startedAt(), old.count() + 1);
            if (window.count() > PER_MINUTE) {
                throw new RateLimitedException((window.startedAt() + WINDOW_MILLIS - now + 999) / 1000);
            }
            windows.put(account, window);
        }
        meters.counter("mnema_experiment_events_total", "key", key, "variant", assignments.variant(account, key),
                "event", event.name()).increment();
        return true;
    }

    /** Removes expired windows, at most once per {@value #SWEEP_MILLIS} ms across all callers. */
    private void sweep(long now) {
        long last = sweptAt.get();
        if (now - last >= SWEEP_MILLIS && sweptAt.compareAndSet(last, now)) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MILLIS);
        }
    }
}
