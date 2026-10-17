package app.mnema.learning.library;

import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The one admission every anonymous-capable public read passes before it asks for a connection: the kill switch (off: 404 as if the route did not exist),
 * the abuse limit (before any lookup, so a flood of unknown codes is limited too) and the bulkhead, the one {@link PublicReadBulkhead} that the media route
 * ({@link PublicMediaService}, which keeps its own limiter and metrics) shares: {@code max-concurrent} places in all, of which guests may hold at most one
 * less, tried BEFORE the transaction opens, so excess reads fail fast with 503 and never queue for a connection, and a signed-in viewer always finds a place.
 * Deck reads ({@link PublicDeckService}), link-preview reads ({@link PublicMetaService}) and media reads together never take more than {@code max-concurrent}
 * places of the pool ({@link PublicRouteConnectionBudget}).
 *
 * <p>Outcomes are counted as {@code mnema_public_deck_requests_total{route,outcome}}; this class counts {@code disabled}, {@code rate_limited} and {@code busy},
 * the caller counts what its own work decides.
 */
@Component
public final class PublicReadAdmission {
    private final PublicRouteSettings settings;
    private final PublicReadLimiter limiter;
    private final MeterRegistry meters;
    private final PublicReadBulkhead bulkhead;

    PublicReadAdmission(PublicRouteSettings settings, PublicReadLimiter limiter, MeterRegistry meters, PublicReadBulkhead bulkhead) {
        this.settings = settings;
        this.limiter = limiter;
        this.meters = meters;
        this.bulkhead = bulkhead;
    }

    /** Runs {@code work} inside a place of the bulkhead; the place is released when it ends, however it ends. */
    public <T> T within(String route, Viewer viewer, Supplier<T> work) {
        requireEnabled(route);
        // A signed-in viewer was admitted by the filter of the public chain before Identity was asked (one token cannot drive unlimited
        // Identity round trips); only guests are counted here.
        if (viewer.isGuest()) {
            try {
                limiter.admit(viewer);
            } catch (RateLimitedException failure) {
                count(route, "rate_limited");
                throw failure;
            }
        }
        // Before a connection or a transaction is asked for: a flood of public reads is refused here, it never queues for the pool. A guest also
        // needs a place of the guest share, so guests alone can never take the last place.
        boolean guest = viewer.isGuest();
        if (!bulkhead.tryAcquire(guest)) {
            count(route, "busy");
            throw new PublicReadBusyException();
        }
        try {
            return work.get();
        } finally {
            bulkhead.release(guest);
        }
    }

    /** Catalogue descendants can check their parent switch before parsing a request. */
    public void requireEnabled(String route) {
        if (!settings.enabled) {
            count(route, "disabled");
            throw new ResourceNotFoundException();
        }
    }

    public void count(String route, String outcome) {
        meters.counter("mnema_public_deck_requests_total", "route", route, "outcome", outcome).increment();
    }
}
