package app.mnema.learning.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Signed playback links of an asset of a PUBLISHED deck (Share/9, community decks architecture sections 8 and 9), for guests and signed-in viewers of
 * someone else's deck. The caller (the public media route) has already proved with {@code DeckAccess} that the viewer may read the deck; this class owns the
 * media half: the asset must be in the deck's {@code published_media} set ({@link MediaCatalog#publishedPlayback}), only DERIVED variants (playback, poster or
 * thumbnail) are signed, never the verified original, and the links are short-lived and not stored. A guest's link lives
 * {@code learning.community.public-media.url-ttl} (default ten minutes, five minutes to two hours as for the owner's playback), shorter than the owner's
 * {@code learning.media.playback.url-ttl}: a link that leaks from a shared page stops working soon, and the page asks again when it needs a new one.
 *
 * <p>States are collapsed for non-owners: {@code READY}, the states of an upload that is still going ({@code PENDING_UPLOAD}, {@code VERIFYING},
 * {@code PROCESSING}) and a single {@code UNAVAILABLE} for {@code REJECTED}, {@code FAILED_RETRYABLE} and {@code DELETED}: a guest learns nothing about why
 * the author's asset is not there.
 */
@Service
public class SharedMediaPlayback {
    private final MediaCatalog catalog;
    private final MediaPlaybackStore store;
    private final Duration urlTtl;

    SharedMediaPlayback(MediaCatalog catalog, MediaPlaybackStore store, @Value("${learning.community.public-media.url-ttl:PT10M}") Duration urlTtl) {
        if (urlTtl == null || urlTtl.compareTo(Duration.ofMinutes(5)) < 0 || urlTtl.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("Invalid public media URL lifetime");
        }
        this.catalog = catalog;
        this.store = store;
        this.urlTtl = urlTtl;
    }

    /**
     * @throws app.mnema.learning.platform.api.ResourceNotFoundException the asset is not in the deck's published set (also for an unknown asset)
     * @throws MediaStorageUnavailableException the object store is not configured or cannot sign
     */
    public Playback playback(UUID deck, UUID asset) {
        var descriptor = catalog.publishedPlayback(deck, asset);
        if (!"READY".equals(descriptor.state())) return new Playback(asset, visible(descriptor.state()), null, null);
        return new Playback(asset, descriptor.state(), sign(descriptor.playable()), descriptor.poster() == null ? null : sign(descriptor.poster()));
    }

    private Source sign(MediaCatalog.BlobLocation location) {
        var signed = store.read(location.objectKey(), false, urlTtl);
        return new Source(signed.url(), signed.expiresAt(), location.mimeType());
    }

    /** What a non-owner is told about the state of the asset. */
    static String visible(String state) {
        return switch (state) {
            case "REJECTED", "FAILED_RETRYABLE", "DELETED" -> "UNAVAILABLE";
            default -> state;
        };
    }

    /** A signed derived variant. */
    public record Source(String url, Instant expiresAt, String mimeType) { }

    /** The public answer: the state of the asset and, once READY, its playback and optional poster. */
    public record Playback(UUID assetId, String state, Source playback, Source poster) { }
}
