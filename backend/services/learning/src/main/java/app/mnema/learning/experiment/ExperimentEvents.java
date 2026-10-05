package app.mnema.learning.experiment;

import app.mnema.learning.platform.api.RateLimitedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts exposures and conversions as {@code mnema_experiment_events_total{key,variant,event}}: a Micrometer counter and nothing else, no
 * database row and no account in a label (cardinality is the experiments times their variants times two). The variant is the server's assignment,
 * never a client claim. An account may send {@value #PER_MINUTE} events a minute per instance; the window is a best-effort guard of one instance, not
 * state, because a counter an account can flood is worthless.
 */
@Service
public class ExperimentEvents {
    public enum Event { EXPOSURE, CONVERSION }

    static final int PER_MINUTE = 30;
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_WINDOWS = 20_000;

    private record Window(long startedAt, int count) { }

    private final ExperimentAssignments assignments;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    ExperimentEvents(ExperimentAssignments assignments, MeterRegistry meters) {
        this(assignments, meters, Clock.systemUTC());
    }

    ExperimentEvents(ExperimentAssignments assignments, MeterRegistry meters, Clock clock) {
        this.assignments = assignments;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @return whether the event was counted; false for an experiment that is not enabled
     * @throws RateLimitedException the account sent too many events this minute
     */
    public boolean record(UUID account, String key, Event event) {
        long now = clock.millis();
        if (windows.size() >= MAX_WINDOWS) windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MILLIS);
        Window window = windows.merge(account, new Window(now, 1),
                (old, fresh) -> now - old.startedAt() >= WINDOW_MILLIS ? fresh : new Window(old.startedAt(), old.count() + 1));
        if (window.count() > PER_MINUTE) {
            throw new RateLimitedException((window.startedAt() + WINDOW_MILLIS - now + 999) / 1000);
        }
        if (!assignments.known(key)) return false;
        meters.counter("mnema_experiment_events_total", "key", key, "variant", assignments.variant(account, key),
                "event", event.name()).increment();
        return true;
    }
}
