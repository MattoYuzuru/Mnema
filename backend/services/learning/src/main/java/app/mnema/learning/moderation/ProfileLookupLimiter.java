package app.mnema.learning.moderation;

import app.mnema.learning.platform.api.RateLimitedException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The budget of the Identity profile lookups that profile complaints make (Share/15): fixed one-minute windows on the monotonic clock, in memory of the instance,
 * with TWO caps, per reporter and per instance. It is deliberately not the budget of the grants by login ({@code LoginLookupLimiter}): Identity limits that public
 * lookup per NETWORK (60 per 15 minutes) and every Learning call comes from one address, so complaint traffic must never starve an owner granting access. The defaults
 * keep the two instance caps (grants 40, complaints 10) below the shared 60; with N instances behind one address divide both by N.
 * A lookup is admitted only when both windows have room. {@code Retry-After} is the rest of the window that refused. The reporter table is bounded: when it is
 * full of live windows a new reporter is refused rather than the memory grown.
 */
@Component
final class ProfileLookupLimiter {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private static final int MAX_TRACKED = 10_000;

    private static final class Window {
        final long startedAt;
        int count;

        Window(long startedAt) { this.startedAt = startedAt; }
    }

    private final int perReporter;
    private final int perInstance;
    private final LongSupplier nanos;
    private final Map<UUID, Window> reporters = new HashMap<>();
    private Window instance;

    @org.springframework.beans.factory.annotation.Autowired
    ProfileLookupLimiter(ComplaintSettings settings) { this(settings.profileLookupsPerReporter, settings.profileLookupsPerInstance, System::nanoTime); }

    ProfileLookupLimiter(int perReporter, int perInstance, LongSupplier nanos) {
        if (perReporter < 1 || perReporter > 1_000 || perInstance < 1 || perInstance > 1_000) throw new IllegalArgumentException("Invalid profile lookup limits");
        this.perReporter = perReporter;
        this.perInstance = perInstance;
        this.nanos = nanos;
    }

    /** @throws RateLimitedException the reporter or the instance used this minute's lookups */
    synchronized void admit(UUID reporter) {
        long now = nanos.getAsLong();
        if (instance == null || now - instance.startedAt >= WINDOW_NANOS) instance = new Window(now);
        if (instance.count >= perInstance) throw new RateLimitedException(rest(instance, now));
        Window window = reporters.get(reporter);
        if (window != null && now - window.startedAt >= WINDOW_NANOS) {
            reporters.remove(reporter);
            window = null;
        }
        if (window == null) {
            if (reporters.size() >= MAX_TRACKED) {
                reporters.values().removeIf(each -> now - each.startedAt >= WINDOW_NANOS);
                if (reporters.size() >= MAX_TRACKED) throw new RateLimitedException(60);
            }
            window = new Window(now);
            reporters.put(reporter, window);
        }
        if (window.count >= perReporter) throw new RateLimitedException(rest(window, now));
        window.count++;
        instance.count++;
    }

    private static long rest(Window window, long now) { return Math.ceilDiv(WINDOW_NANOS - (now - window.startedAt), 1_000_000_000L); }
}
