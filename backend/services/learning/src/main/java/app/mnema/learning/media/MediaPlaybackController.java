package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Public API only for an authenticated owner's logical asset; S3 locations remain internal. */
@RestController
@RequestMapping(value = "/media-assets", produces = MediaType.APPLICATION_JSON_VALUE)
public final class MediaPlaybackController {
    private final MediaCatalog catalog;
    private final MediaPlaybackStore store;

    MediaPlaybackController(MediaCatalog catalog, MediaPlaybackStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    @GetMapping("/{assetId}/playback")
    ResponseEntity<PlaybackView> playback(@AuthenticationPrincipal Jwt identity, @PathVariable String assetId) {
        UUID asset = id(assetId);
        var descriptor = catalog.playback(id(identity.getSubject()), asset);
        if (!"READY".equals(descriptor.state())) return privateOk(new PlaybackView(asset, descriptor.state(),
                null, null, null));
        var playable = descriptor.playable();
        var signed = store.read(playable.objectKey(), false);
        SignedSource source = new SignedSource(signed.url(), signed.expiresAt(), playable.mimeType());
        SignedSource poster = null;
        if (descriptor.poster() != null) {
            var signedPoster = store.read(descriptor.poster().objectKey(), false);
            poster = new SignedSource(signedPoster.url(), signedPoster.expiresAt(), descriptor.poster().mimeType());
        }
        var original = store.read(descriptor.original().objectKey(), true);
        return privateOk(new PlaybackView(asset, descriptor.state(), source, poster,
                new SignedSource(original.url(), original.expiresAt(), descriptor.original().mimeType())));
    }

    /** Offline clients request each pinned variant just in time; URLs never enter a manifest. */
    @GetMapping("/{assetId}/variants/{variantId}/download")
    ResponseEntity<SignedSource> variant(@AuthenticationPrincipal Jwt identity,
                                         @PathVariable String assetId, @PathVariable String variantId) {
        var location = catalog.resolve(id(identity.getSubject()), id(assetId), id(variantId));
        var signed = store.read(location.objectKey(), true);
        return privateOk(new SignedSource(signed.url(), signed.expiresAt(), location.mimeType()));
    }

    private static <T> ResponseEntity<T> privateOk(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }

    record SignedSource(String url, Instant expiresAt, String mimeType) { }
    record PlaybackView(UUID assetId, String state, SignedSource playback, SignedSource poster,
                        SignedSource download) { }
}
