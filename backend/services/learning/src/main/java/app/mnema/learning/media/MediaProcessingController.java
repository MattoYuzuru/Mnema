package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.UUID;

/** Requeue preserved bytes after the automatic processing attempt budget is exhausted. */
@RestController
@RequestMapping(value = "/media-assets", produces = MediaType.APPLICATION_JSON_VALUE)
final class MediaProcessingController {
    private final MediaProcessingService processing;
    private final MediaUploadService uploads;

    MediaProcessingController(MediaProcessingService processing, MediaUploadService uploads) {
        this.processing = processing;
        this.uploads = uploads;
    }

    @PostMapping(value = "/{assetId}/processing/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MediaUploadService.UploadView> retry(@AuthenticationPrincipal Jwt identity,
                                                         @PathVariable String assetId, InputStream body) {
        UUID owner = id(identity.getSubject());
        UUID asset = id(assetId);
        processing.retryPreserved(owner, asset, MediaUploadCommand.generation(body));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(uploads.status(owner, asset));
    }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }
}
