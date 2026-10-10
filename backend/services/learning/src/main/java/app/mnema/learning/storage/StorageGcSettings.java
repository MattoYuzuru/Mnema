package app.mnema.learning.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Pace of the background reclamation of unreachable immutable storage (the schedule itself is
 * {@code learning.storage.gc.initial-delay} and {@code interval}, read by {@link StorageGcSchedule}). These bound one pass; they
 * are engineering safeguards for a single database, not a product retention number. Reachability is never configurable.
 */
@Component
final class StorageGcSettings {
    final boolean enabled;
    final Duration grace;
    final int maxScopes;
    final int maxBatchesPerScope;
    final Duration maxRun;

    StorageGcSettings(
            @Value("${learning.storage.gc.enabled:true}") boolean enabled,
            @Value("${learning.storage.gc.grace:PT1H}") Duration grace,
            @Value("${learning.storage.gc.max-scopes:8}") int maxScopes,
            @Value("${learning.storage.gc.max-batches-per-scope:16}") int maxBatchesPerScope,
            @Value("${learning.storage.gc.max-run:PT30S}") Duration maxRun) {
        if (grace == null || grace.isNegative() || grace.compareTo(Duration.ofDays(30)) > 0
                || maxScopes < 1 || maxScopes > 64 || maxBatchesPerScope < 1 || maxBatchesPerScope > 256
                || maxRun == null || maxRun.compareTo(Duration.ofSeconds(1)) < 0 || maxRun.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid storage GC policy");
        }
        this.enabled = enabled;
        this.grace = grace;
        this.maxScopes = maxScopes;
        this.maxBatchesPerScope = maxBatchesPerScope;
        this.maxRun = maxRun;
    }
}
