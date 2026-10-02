package app.mnema.learning.ai;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * Global daily spend guard per capability. The authoritative sum is the call journal (all instances); a value is cached
 * for {@code cacheTtl} and bumped locally by every call this instance completes, so a burst cannot sail past the limit
 * between refreshes on one instance. Zero limit means no limit. A failing source never blocks calls (fail open: the
 * provider-side spend cap is the last fuse), and is logged by the caller of the source.
 */
final class AiBudget {
    /** Micro-dollars spent on one capability since {@code since}. */
    @FunctionalInterface
    interface SpendSource {
        long spentMicros(AiCapability capability, Instant since);
    }

    private final AiProperties.Budget policy;
    private final SpendSource source;
    private final Clock clock;
    private final Map<AiCapability, Cached> cache = new EnumMap<>(AiCapability.class);

    AiBudget(AiProperties.Budget policy, SpendSource source, Clock clock) {
        this.policy = policy;
        this.source = source;
        this.clock = clock;
    }

    boolean exhausted(AiCapability capability) {
        long limit = policy.of(capability);
        if (limit <= 0) return false;
        return spent(capability) >= limit;
    }

    /** Adds the cost of a completed call to this instance's view of today's spend. */
    synchronized void record(AiCapability capability, long costMicros) {
        Cached cached = cache.get(capability);
        if (cached != null && costMicros > 0) {
            cache.put(capability, new Cached(cached.day, cached.fetchedAt, cached.spent + costMicros));
        }
    }

    private synchronized long spent(AiCapability capability) {
        Instant now = clock.instant();
        LocalDate day = now.atZone(policy.zoneId()).toLocalDate();
        Cached cached = cache.get(capability);
        if (cached == null || !cached.day.equals(day) || !now.isBefore(cached.fetchedAt.plus(policy.cacheTtl()))) {
            Instant since = day.atStartOfDay(policy.zoneId()).toInstant();
            long spent;
            try {
                spent = source.spentMicros(capability, since);
            } catch (RuntimeException exception) {
                spent = cached != null && cached.day.equals(day) ? cached.spent : 0;
            }
            cached = new Cached(day, now, spent);
            cache.put(capability, cached);
        }
        return cached.spent;
    }

    private record Cached(LocalDate day, Instant fetchedAt, long spent) { }
}
