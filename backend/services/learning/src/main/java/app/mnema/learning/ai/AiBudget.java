package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Global daily spend guard per capability. The authoritative sum is the call journal (all instances); a value is cached
 * for {@code cacheTtl} and bumped locally by every call this instance completes, so a burst cannot sail past the limit
 * between refreshes on one instance. Zero limit means no limit.
 *
 * <p>The refresh queries the source <em>outside</em> any shared monitor and is single-flight per capability: while one
 * caller refreshes, others keep reading the stale value of the same day; only the very first read of a day waits. A
 * failing source never blocks calls (fail open: the provider-side spend cap is the last fuse): the last known value of the
 * day, or zero, is used and one WARN with the exception class is logged per failed refresh.
 */
final class AiBudget {
    private static final Logger LOG = LoggerFactory.getLogger(AiBudget.class);

    /** Micro-dollars spent on one capability since {@code since}. */
    @FunctionalInterface
    interface SpendSource {
        long spentMicros(AiCapability capability, Instant since);
    }

    private final AiProperties.Budget policy;
    private final SpendSource source;
    private final Clock clock;
    private final Map<AiCapability, Cached> cache = new EnumMap<>(AiCapability.class);
    private final Map<AiCapability, ReentrantLock> refreshes = new EnumMap<>(AiCapability.class);

    AiBudget(AiProperties.Budget policy, SpendSource source, Clock clock) {
        this.policy = policy;
        this.source = source;
        this.clock = clock;
        for (AiCapability capability : AiCapability.values()) refreshes.put(capability, new ReentrantLock());
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

    private long spent(AiCapability capability) {
        Instant now = clock.instant();
        LocalDate day = now.atZone(policy.zoneId()).toLocalDate();
        Cached cached = read(capability);
        boolean sameDay = cached != null && cached.day.equals(day);
        if (sameDay && fresh(cached, now)) return cached.spent;
        ReentrantLock refresh = refreshes.get(capability);
        if (sameDay) {
            if (!refresh.tryLock()) return cached.spent;
        } else {
            refresh.lock();
        }
        try {
            Cached again = read(capability);
            if (again != null && again.day.equals(day) && fresh(again, now)) return again.spent;
            long spent;
            try {
                spent = source.spentMicros(capability, day.atStartOfDay(policy.zoneId()).toInstant());
            } catch (RuntimeException exception) {
                LOG.warn("ai_budget_refresh_failed capability={} error_type={}", capability.label(), exception.getClass().getSimpleName());
                spent = sameDay ? cached.spent : 0;
            }
            write(capability, new Cached(day, now, spent));
            return spent;
        } finally {
            refresh.unlock();
        }
    }

    private boolean fresh(Cached cached, Instant now) { return now.isBefore(cached.fetchedAt.plus(policy.cacheTtl())); }

    private synchronized Cached read(AiCapability capability) { return cache.get(capability); }

    private synchronized void write(AiCapability capability, Cached cached) { cache.put(capability, cached); }

    private record Cached(LocalDate day, Instant fetchedAt, long spent) { }
}
