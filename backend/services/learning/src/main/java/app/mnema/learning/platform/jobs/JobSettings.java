package app.mnema.learning.platform.jobs;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code learning.jobs.*}: the kill switch, {@code max-concurrency} [4, 1-64] slices the process runs at once in all queues, the pace of the sweeps, the retention of finished jobs and, per queue, {@code learning.jobs.<queue>.*}.
 * The queue is a resource class, so its policy is configuration of that class (a queue name may contain dots and dashes, which the
 * {@link Environment} lookup keeps as written). Everything is validated when first read; the executor reads every queue it serves at start, so a bad
 * value stops the start rather than a job at night.
 *
 * <p>Per queue (defaults in brackets): {@code enabled} [true] the queue's kill switch (a disabled queue is neither claimed nor swept; its jobs wait),
 * {@code concurrency} [2, 1-64] jobs of the queue this process runs at once, {@code lease} [PT60S, 5 s-15 min] how long a worker owns a job without
 * committing a slice, {@code max-attempts} [5, 1-100] claims before FAILED (read when a job is enqueued), {@code backoff} [PT10S, 100 ms-1 h] the
 * first delay of a retry, doubling per attempt up to {@code backoff-max} [PT10M, at least the backoff, at most 1 day].
 */
@Component
public class JobSettings {
    static final String PREFIX = "learning.jobs.";

    /** The policy of one queue. */
    record Queue(boolean enabled, int concurrency, Duration lease, int maxAttempts, Duration backoff, Duration backoffMax) {
    }

    private final boolean enabled;
    private final Duration sweepInterval;
    private final Duration retention;
    private final int maxConcurrency;
    private final Environment environment;
    private final Map<String, Queue> queues = new ConcurrentHashMap<>();

    public JobSettings(Environment environment) {
        this.environment = environment;
        this.enabled = bool(PREFIX + "enabled", true);
        this.sweepInterval = duration(PREFIX + "sweep-interval", "PT2S", Duration.ofMillis(100), Duration.ofMinutes(5));
        this.retention = duration(PREFIX + "retention", "P7D", Duration.ofHours(1), Duration.ofDays(365));
        this.maxConcurrency = integer(PREFIX + "max-concurrency", 4, 1, 64);
    }

    /** The global kill switch: false stops every claim, sweep and retention pass of this process; jobs keep being accepted and wait. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Slices this process runs at once across all queues. Every running slice holds one pooled database connection, so this stays below the pool size
     * (Hikari's default is 10) with a reserve for requests and the other workers.
     */
    int maxConcurrency() {
        return maxConcurrency;
    }

    Duration sweepInterval() {
        return sweepInterval;
    }

    /** How long a finished job is kept for its status before retention deletes it. */
    Duration retention() {
        return retention;
    }

    Queue queue(String name) {
        return queues.computeIfAbsent(JobCodes.queue(name), this::read);
    }

    private static final java.util.Set<String> GLOBAL_KEYS = java.util.Set.of("enabled", "sweep-interval", "retention", "max-concurrency");
    private static final java.util.Set<String> QUEUE_KEYS = java.util.Set.of("enabled", "concurrency", "lease", "max-attempts", "backoff", "backoff-max");

    /**
     * The configured {@code learning.jobs.*} keys that govern nothing in this process: a queue name that no handler serves (a typo'd kill switch would
     * otherwise be silent) or a setting name that does not exist. Names only, never values.
     */
    java.util.SortedSet<String> unmatchedKeys(java.util.Set<String> servedQueues) {
        java.util.SortedSet<String> found = new java.util.TreeSet<>();
        if (!(environment instanceof org.springframework.core.env.ConfigurableEnvironment configurable)) return found;
        for (org.springframework.core.env.PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable)) continue;
            for (String key : enumerable.getPropertyNames()) {
                if (!key.startsWith(PREFIX)) continue;
                String rest = key.substring(PREFIX.length());
                if (GLOBAL_KEYS.contains(rest)) continue;
                int dot = rest.lastIndexOf('.');
                String queue = dot < 0 ? "" : rest.substring(0, dot);
                String setting = dot < 0 ? rest : rest.substring(dot + 1);
                if (QUEUE_KEYS.contains(setting) && servedQueues.contains(queue)) continue;
                found.add(key);
            }
        }
        return found;
    }

    private Queue read(String name) {
        String prefix = PREFIX + name + ".";
        Queue queue = new Queue(bool(prefix + "enabled", true), integer(prefix + "concurrency", 2, 1, 64),
                duration(prefix + "lease", "PT60S", Duration.ofSeconds(5), Duration.ofMinutes(15)),
                integer(prefix + "max-attempts", 5, 1, 100),
                duration(prefix + "backoff", "PT10S", Duration.ofMillis(100), Duration.ofHours(1)),
                duration(prefix + "backoff-max", "PT10M", Duration.ofMillis(100), Duration.ofDays(1)));
        if (queue.backoffMax().compareTo(queue.backoff()) < 0) throw new IllegalArgumentException("Invalid " + prefix + "backoff-max: below the backoff");
        return queue;
    }

    private boolean bool(String key, boolean fallback) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid " + key);
        };
    }

    private int integer(String key, int fallback, int min, int max) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) throw new IllegalArgumentException("Invalid " + key);
            return parsed;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Invalid " + key);
        }
    }

    private Duration duration(String key, String fallback, Duration min, Duration max) {
        String value = environment.getProperty(key);
        try {
            Duration parsed = Duration.parse(value == null || value.isBlank() ? fallback : value.trim());
            if (parsed.compareTo(min) < 0 || parsed.compareTo(max) > 0) throw new IllegalArgumentException("Invalid " + key);
            return parsed;
        } catch (DateTimeException malformed) {
            throw new IllegalArgumentException("Invalid " + key);
        }
    }
}
