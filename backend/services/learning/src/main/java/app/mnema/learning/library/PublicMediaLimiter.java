package app.mnema.learning.library;

import app.mnema.learning.platform.api.RateLimitedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The abuse limit of the public media route (Share/9): fixed one-minute windows on the monotonic clock ({@link System#nanoTime}), in the memory of the
 * instance for the reason {@link PublicReadLimiter} gives (a database write per read would turn the cheapest request into the most expensive one). It counts
 * requests for signed links, not bytes. Three tables, all bounded: a guest per client network, all guests of one IPv6 /48 together (a request must pass both,
 * so rotating /64s inside a site is bounded, exactly as in the read limiter) and all guests together per deck. A key that finds its table full is folded into
 * the table's shared overflow bucket instead of being refused one by one, so the memory is bounded and an address flood degrades new guests together. Every
 * request counts against its network, found or not (the deck is known only after the code is resolved, so a request the deck window refuses has already been
 * counted for its network); within one call every window is checked before any is charged, so a refusing window leaves the others alone. {@code Retry-After}
 * is the real remaining time of the refusing window.
 */
@Component
final class PublicMediaLimiter {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private static final long SWEEP_NANOS = 5_000_000_000L;

    private static final class Window {
        long startedAt;
        int count;

        Window(long startedAt) { this.startedAt = startedAt; }
    }

    /** A place of a table: the window (new when the key had none), where it goes and its limit. */
    private record Slot(Map<String, Window> table, String key, Window window, boolean keep, int limit) { }

    private final PublicMediaSettings settings;
    private final MeterRegistry meters;
    private final LongSupplier nanos;
    private final Map<String, Window> networks = new HashMap<>();
    private final Map<String, Window> coarse = new HashMap<>();
    private final Map<String, Window> decks = new HashMap<>();
    private final Window networkOverflow;
    private final Window coarseOverflow;
    private final Window deckOverflow;
    private long sweptAt;

    @Autowired
    PublicMediaLimiter(PublicMediaSettings settings, MeterRegistry meters) { this(settings, System::nanoTime, meters); }

    PublicMediaLimiter(PublicMediaSettings settings, LongSupplier nanos, MeterRegistry meters) {
        this.settings = settings;
        this.meters = meters;
        this.nanos = nanos;
        this.sweptAt = nanos.getAsLong();
        this.networkOverflow = new Window(sweptAt);
        this.coarseOverflow = new Window(sweptAt);
        this.deckOverflow = new Window(sweptAt);
    }

    int tracked() {
        synchronized (this) { return networks.size() + coarse.size() + decks.size(); }
    }

    int trackedCoarse() {
        synchronized (this) { return coarse.size(); }
    }

    /** Counts a guest request against its client network and, for IPv6, against the /48 around it. */
    void admitNetwork(Viewer viewer) {
        long now = nanos.getAsLong();
        synchronized (this) {
            sweepIfFull(now);
            Slot fine = slot(networks, "n:" + (viewer.network() == null ? "unknown" : viewer.network()), settings.guestPerMinute, networkOverflow, "network", now);
            Slot wide = viewer.coarse() == null ? null
                    : slot(coarse, "c:" + viewer.coarse(), settings.coarsePerMinute, coarseOverflow, "coarse", now);
            charge(wide == null ? List.of(fine) : fine.window() == wide.window() ? List.of(fine) : List.of(fine, wide), now);
        }
    }

    /** Counts a guest request against the deck it asks for. */
    void admitDeck(UUID deck) {
        long now = nanos.getAsLong();
        synchronized (this) {
            sweepIfFull(now);
            charge(List.of(slot(decks, "d:" + deck, settings.deckPerMinute, deckOverflow, "deck", now)), now);
        }
    }

    /** Looks the key up; a new key finds room or the overflow bucket of its table. */
    private Slot slot(Map<String, Window> table, String key, int limit, Window overflow, String name, long now) {
        Window window = table.get(key);
        if (window != null) return new Slot(table, key, window, true, limit);
        if (table.size() < settings.maxTracked) return new Slot(table, key, new Window(now), true, limit);
        event(name + "_table_full");
        return new Slot(table, key, overflow, false, settings.overflowPerMinute);
    }

    /** Checks every window first, so a refused request charges nothing, then counts and (re)stores the windows that belong in a table. */
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
            if (slot.keep()) slot.table().put(slot.key(), slot.window());
            else event(slot.table() == networks ? "network_overflow_bucket" : slot.table() == coarse ? "coarse_overflow_bucket" : "deck_overflow_bucket");
        }
    }

    private void sweepIfFull(long now) {
        int bound = settings.maxTracked;
        if (networks.size() < bound && coarse.size() < bound && decks.size() < bound) return;
        if (now - sweptAt < SWEEP_NANOS) return;
        sweptAt = now;
        for (Map<String, Window> table : List.of(networks, coarse, decks)) table.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_NANOS);
    }

    /** Limiter events are counted on {@code mnema_public_media_requests_total} with {@code outcome="limiter_<event>"}; the labels are fixed names, never a key. */
    private void event(String outcome) {
        meters.counter("mnema_public_media_requests_total", "outcome", "limiter_" + outcome).increment();
    }
}
