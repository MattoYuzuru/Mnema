package app.mnema.learning.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Engineering safeguards for unpublished physical staging; never a durable-content TTL. */
@Component
public final class StorageSettings {
    private final Duration maxLease;
    private final Duration orphanGrace;
    private final Duration lockTimeout;

    public StorageSettings(
            @Value("${learning.storage.max-staging-lease:PT1H}") Duration maxLease,
            @Value("${learning.storage.orphan-grace:PT10M}") Duration orphanGrace,
            @Value("${learning.storage.lock-timeout:PT1S}") Duration lockTimeout
    ) {
        this.maxLease = bounded(maxLease, Duration.ofDays(1));
        this.orphanGrace = bounded(orphanGrace, Duration.ofDays(7));
        this.lockTimeout = bounded(lockTimeout, Duration.ofSeconds(5));
    }

    public Duration requireLease(Duration lease) {
        return bounded(lease, maxLease);
    }

    public Duration orphanGrace() {
        return orphanGrace;
    }

    public Duration lockTimeout() {
        return lockTimeout;
    }

    private static Duration bounded(Duration duration, Duration maximum) {
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0 || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid storage duration");
        }
        return duration;
    }
}
