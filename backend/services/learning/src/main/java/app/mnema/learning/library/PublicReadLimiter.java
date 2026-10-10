package app.mnema.learning.library;

import app.mnema.learning.platform.api.RateLimitedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The abuse limit of the public read routes: fixed one-minute windows measured on the monotonic clock ({@link System#nanoTime}, so a wall-clock step
 * can neither reopen nor freeze a window). Every request counts, found or not.
 * <ul>
 *   <li><b>Accounts</b> have a table of their own, so a flood of guests can never starve a signed-in viewer.</li>
 *   <li><b>Guests</b> are counted per client network (IPv4 address, IPv6 /64) and, for IPv6, also per /48 in a second table: a request must pass both,
 *       so rotating /64s inside one site is bounded by the /48 budget.</li>
 *   <li>When a guest table is full, a new key is not refused: it is folded into one shared <b>overflow</b> bucket with its own cap, so an address
 *       flood degrades new guests together instead of locking every one of them out, and the memory stays bounded.</li>
 * </ul>
 * {@code Retry-After} is the real remaining time of the window that refused the request.
 *
 * <p>The windows live in memory of the instance, like {@code ExperimentEvents}, and not in the database like {@code PromoAttempts}: promo attempts are
 * rare writes that must be exact across instances, while this limiter sits in front of every read of a route meant for ~25 rps at peak, where a
 * database write per read would make the cheapest request the most expensive and give a flood a write amplifier. The cost of the choice is that a
 * limit holds per instance (N instances allow N times the limit), which is acceptable for a guard against scraping and flooding, not an accounting figure.
 */
@Component
final class PublicReadLimiter {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private static final long SWEEP_NANOS = 5_000_000_000L;

    private static final class Window {
        long startedAt;
        int count;

        Window(long startedAt) { this.startedAt = startedAt; }
    }

    private final PublicRouteSettings settings;
    private final LongSupplier nanos;
    private final Map<String, Window> guests = new HashMap<>();
    private final Map<String, Window> networks = new HashMap<>();
    private final Map<String, Window> accounts = new HashMap<>();
    private final Window overflow;
    private long sweptAt;

    @Autowired
    PublicReadLimiter(PublicRouteSettings settings) { this(settings, System::nanoTime); }

    PublicReadLimiter(PublicRouteSettings settings, LongSupplier nanos) {
        this.settings = settings;
        this.nanos = nanos;
        this.sweptAt = nanos.getAsLong();
        this.overflow = new Window(sweptAt);
    }

    int tracked() {
        synchronized (this) { return guests.size() + networks.size() + accounts.size(); }
    }

    int trackedAccounts() {
        synchronized (this) { return accounts.size(); }
    }

    int trackedGuests() {
        synchronized (this) { return guests.size(); }
    }

    /** A place of a table: the window (new when the key had none), where it goes and its limit. */
    private record Slot(Map<String, Window> table, String key, Window window, boolean added, int limit) { }

    /** @throws RateLimitedException the viewer's window (or the shared overflow window) is full, or the account table has no room for a new key */
    void admit(Viewer viewer) {
        long now = nanos.getAsLong();
        synchronized (this) {
            if (viewer.accountId() != null) {
                charge(slots(slot(accounts, "a:" + viewer.accountId(), settings.accountPerMinute, now, false)), now);
                return;
            }
            Slot fine = slot(guests, "g:" + (viewer.network() == null ? "unknown" : viewer.network()), settings.guestPerMinute, now, true);
            Slot coarse = viewer.coarse() == null ? null : slot(networks, "n:" + viewer.coarse(), settings.coarsePerMinute, now, true);
            charge(coarse == null ? slots(fine) : fine.window() == coarse.window() ? slots(fine) : slots(fine, coarse), now);
        }
    }

    /** Looks the key up (an expired window is a fresh one); a new key finds room, the overflow bucket (guests) or a refusal (accounts). */
    private Slot slot(Map<String, Window> table, String key, int limit, long now, boolean foldIntoOverflow) {
        Window window = table.get(key);
        if (window != null) return new Slot(table, key, window, false, limit);
        if (table.size() >= settings.maxTracked) sweep(now);
        if (table.size() < settings.maxTracked) return new Slot(table, key, new Window(now), true, limit);
        if (foldIntoOverflow) return new Slot(table, key, overflow, false, settings.overflowPerMinute);
        throw new RateLimitedException(SWEEP_NANOS / 1_000_000_000L);
    }

    /** Checks every window first, so a refused request charges nothing, then counts and stores the new ones. */
    private void charge(List<Slot> slots, long now) {
        for (Slot slot : slots) {
            Window window = slot.window();
            if (now - window.startedAt >= WINDOW_NANOS) {
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count + 1 > slot.limit()) {
                throw new RateLimitedException(Math.max(1, (window.startedAt + WINDOW_NANOS - now + 999_999_999L) / 1_000_000_000L));
            }
        }
        for (Slot slot : slots) {
            slot.window().count++;
            if (slot.added()) slot.table().put(slot.key(), slot.window());
        }
    }

    private void sweep(long now) {
        if (now - sweptAt < SWEEP_NANOS) return;
        sweptAt = now;
        for (Map<String, Window> table : List.of(guests, networks, accounts)) {
            table.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_NANOS);
        }
    }

    private static List<Slot> slots(Slot... slots) { return List.of(slots); }
}
