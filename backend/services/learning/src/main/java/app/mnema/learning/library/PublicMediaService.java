package app.mnema.learning.library;

import app.mnema.learning.media.SharedMediaPlayback;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Media of someone else's PUBLISHED deck for guests and signed-in viewers (Share/9, #431; {@code GET /api/public/decks/{code}/media/{assetId}}, contract
 * {@code contracts/decks/public-read.json}). The answer is {@code {assetId, state, playback|null, poster|null}} with short-lived signed URLs of DERIVED
 * variants (playback, poster or thumbnail), never the original and never anything the owner's private routes serve.
 *
 * <p>Authorization is two probes and fails closed: {@link DeckAccess} must grant a level for the deck behind the code (a published revision at any level),
 * and the asset must be in the deck's {@code published_media} set, the assets of the published MATERIALS ({@link SharedMediaPlayback}); exercise media is not offered. Everything else is the same 404: an unknown or rotated
 * code, a private or invitation-only deck (a media request never says that a deck exists, so {@code INVITE_ONLY} is a 404 here, unlike the reads), a deck
 * without a published revision, an asset that is not in the published set (an asset only the owner's head has, one removed by a republication, one of another
 * deck) and an unknown asset id. A malformed asset id is 400 like every malformed identifier of the public routes.
 *
 * <p>Order: the kill switches (the route needs {@code public-routes.enabled} and {@code public-media.enabled}; off: 404 as if absent), the guest's network
 * window (before any lookup, so unknown codes cost a window too), the shared bulkhead (the excess is 503 before any connection), one short read-only
 * transaction that resolves access, the guest's deck window, and the published-set probe with the variant lookup. Outcomes are counted as
 * {@code mnema_public_media_requests_total{outcome}} (ok, not_found, rate_limited, busy, disabled, unavailable); neither the code nor the asset is a label.
 *
 * <p>Load: zero published decks, the table is empty and a request is one unique-index miss. Scenario H (~25 rps of reads, a page of a deck holds up to a few
 * dozen media): the probes are the code index, the deck key, one {@code published_media} primary key and the variants of one asset; the network, /48 and
 * deck windows bound how fast links are handed out per instance (they count requests, not bytes: the bytes come from the object store) and a CDN for the
 * derived variants stays a separate decision.
 */
@Service
public class PublicMediaService {
    private final PublicRouteSettings routes;
    private final PublicMediaSettings settings;
    private final PublicMediaLimiter limiter;
    private final PublicReadBulkhead bulkhead;
    private final DeckAccess access;
    private final SharedMediaPlayback playback;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;

    PublicMediaService(PublicRouteSettings routes, PublicMediaSettings settings, PublicMediaLimiter limiter, PublicReadBulkhead bulkhead,
                       DeckAccess access, SharedMediaPlayback playback, MeterRegistry meters, PlatformTransactionManager transactions) {
        this.routes = routes;
        this.settings = settings;
        this.limiter = limiter;
        this.bulkhead = bulkhead;
        this.access = access;
        this.playback = playback;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.transaction.setReadOnly(true);
    }

    public ObjectNode media(Viewer viewer, String code, String assetId) {
        if (!routes.enabled || !settings.enabled) {
            count("disabled");
            throw new ResourceNotFoundException();
        }
        // A signed-in viewer was admitted by the filter of the public chain before Identity was asked; only guests are counted here.
        boolean guest = viewer.isGuest();
        if (guest) {
            try {
                limiter.admitNetwork(viewer);
            } catch (RateLimitedException failure) {
                count("rate_limited");
                throw failure;
            }
        }
        if (!bulkhead.tryAcquire(guest)) {
            count("busy");
            throw new PublicReadBusyException();
        }
        try {
            UUID asset = assetId(assetId);
            // One short read-only transaction: the access decision, then the guest's deck window, then the published-set probe and the variants.
            SharedMediaPlayback.Playback served = transaction.execute(status -> {
                Resolution resolution = access.resolve(viewer, code);
                if (!resolution.granted() || resolution.deck().published() == null) throw new ResourceNotFoundException();
                UUID deck = resolution.deck().deckId();
                if (guest) limiter.admitDeck(deck);
                return playback.playback(deck, asset);
            });
            ObjectNode result = JsonNodeFactory.instance.objectNode().put("assetId", served.assetId().toString()).put("state", served.state());
            put(result, "playback", served.playback());
            put(result, "poster", served.poster());
            count("ok");
            return result;
        } catch (ResourceNotFoundException failure) {
            count("not_found");
            throw failure;
        } catch (RateLimitedException failure) {
            count("rate_limited");
            throw failure;
        } catch (app.mnema.learning.media.MediaStorageUnavailableException failure) {
            count("unavailable");
            throw failure;
        } finally {
            bulkhead.release(guest);
        }
    }

    private static void put(ObjectNode result, String name, SharedMediaPlayback.Source source) {
        if (source == null) {
            result.putNull(name);
            return;
        }
        result.putObject(name).put("url", source.url()).put("expiresAt", source.expiresAt().toString()).put("mimeType", source.mimeType());
    }

    private static UUID assetId(String value) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(value), "assetId");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private void count(String outcome) {
        meters.counter("mnema_public_media_requests_total", "outcome", outcome).increment();
    }
}
