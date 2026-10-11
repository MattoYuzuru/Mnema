package app.mnema.learning.library;

import app.mnema.learning.platform.api.RateLimitedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The budget of the login lookups of «Дать доступ по логину»: fixed one-minute windows on the monotonic clock, in memory of the instance, with TWO caps.
 * <ul>
 *   <li>per owner ({@code learning.community.grants.owner-lookups-per-minute}, default 10): one owner can neither enumerate logins nor spend the shared budget;</li>
 *   <li>per instance ({@code learning.community.grants.instance-lookups-per-minute}, default 40): the lookup is Identity's public endpoint, which limits per NETWORK
 *       (60 a minute), and every Learning call comes from the same address, so all owners of all instances behind that address share that one budget. The
 *       instance cap keeps this instance below it and leaves headroom for the others; with N instances behind one address, set it to about 40 / N.</li>
 * </ul>
 * A lookup is admitted only when both windows have room, and then it takes a place in both. {@code Retry-After} is the rest of the window that refused. The owner
 * table is bounded; when it is full of live windows a new owner is refused rather than the memory grown.
 */
@Component
public final class LoginLookupLimiter {
    static final int DEFAULT_PER_OWNER = 10;
    static final int DEFAULT_PER_INSTANCE = 40;
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private static final int MAX_TRACKED = 10_000;

    private static final class Window {
        long startedAt;
        int count;

        Window(long startedAt) { this.startedAt = startedAt; }
    }

    private final int perOwner;
    private final int perInstance;
    private final LongSupplier nanos;
    private final Map<UUID, Window> owners = new HashMap<>();
    private Window instance;

    @Autowired
    LoginLookupLimiter(@Value("${learning.community.grants.owner-lookups-per-minute:" + DEFAULT_PER_OWNER + "}") int perOwner,
                       @Value("${learning.community.grants.instance-lookups-per-minute:" + DEFAULT_PER_INSTANCE + "}") int perInstance) {
        this(perOwner, perInstance, System::nanoTime);
    }

    LoginLookupLimiter() { this(DEFAULT_PER_OWNER, DEFAULT_PER_INSTANCE, System::nanoTime); }

    LoginLookupLimiter(int perOwner, int perInstance, LongSupplier nanos) {
        if (perOwner < 1 || perOwner > 1_000 || perInstance < 1 || perInstance > 1_000) throw new IllegalArgumentException("Invalid grant lookup limits");
        this.perOwner = perOwner;
        this.perInstance = perInstance;
        this.nanos = nanos;
    }

    /** @throws RateLimitedException the owner or the instance used this minute's lookups; {@code Retry-After} is the rest of that window */
    public synchronized void admit(UUID owner) {
        long now = nanos.getAsLong();
        if (instance == null || now - instance.startedAt >= WINDOW_NANOS) instance = new Window(now);
        if (instance.count >= perInstance) throw new RateLimitedException(rest(instance, now));
        Window window = owners.get(owner);
        if (window != null && now - window.startedAt >= WINDOW_NANOS) {
            owners.remove(owner);
            window = null;
        }
        if (window == null) {
            if (owners.size() >= MAX_TRACKED) {
                owners.values().removeIf(each -> now - each.startedAt >= WINDOW_NANOS);
                if (owners.size() >= MAX_TRACKED) throw new RateLimitedException(60);
            }
            window = new Window(now);
            owners.put(owner, window);
        }
        if (window.count >= perOwner) throw new RateLimitedException(rest(window, now));
        window.count++;
        instance.count++;
    }

    private static long rest(Window window, long now) { return Math.ceilDiv(WINDOW_NANOS - (now - window.startedAt), 1_000_000_000L); }
}
