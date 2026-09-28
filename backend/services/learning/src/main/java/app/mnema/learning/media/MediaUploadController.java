package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = "/media-assets", produces = MediaType.APPLICATION_JSON_VALUE)
public final class MediaUploadController {
    private final MediaUploadService uploads;

    MediaUploadController(MediaUploadService uploads) { this.uploads = uploads; }

    @PostMapping(value = "/upload-intents", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MediaUploadService.UploadView> start(@AuthenticationPrincipal Jwt identity,
                                                         InputStream body) {
        var request = MediaUploadCommand.start(body);
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(uploads.start(actor(identity), request.intentId(), request.origin(), request.kind(),
                        request.mime(), request.byteLength()));
    }

    @PostMapping(value = "/{assetId}/upload/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MediaUploadService.UploadView> retry(@AuthenticationPrincipal Jwt identity,
                                                         @PathVariable String assetId, InputStream body) {
        var request = MediaUploadCommand.retry(body);
        return privateOk(uploads.retry(actor(identity), id(assetId), request.commandId(), request.kind(),
                request.mime(), request.byteLength()));
    }

    @GetMapping("/{assetId}/upload")
    ResponseEntity<MediaUploadService.UploadView> status(@AuthenticationPrincipal Jwt identity,
                                                          @PathVariable String assetId) {
        return privateOk(uploads.status(actor(identity), id(assetId)));
    }

    @PostMapping(value = "/{assetId}/upload/url", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MediaUploadService.UploadView> singleUrl(@AuthenticationPrincipal Jwt identity,
                                                             @PathVariable String assetId, InputStream body) {
        return privateOk(uploads.singleUrl(actor(identity), id(assetId), MediaUploadCommand.generation(body)));
    }

    @GetMapping("/{assetId}/upload/parts")
    ResponseEntity<List<Integer>> uploadedParts(@AuthenticationPrincipal Jwt identity, @PathVariable String assetId,
                                                 @RequestParam long generation) {
        return privateOk(uploads.uploadedParts(actor(identity), id(assetId), generation));
    }

    @PostMapping(value = "/{assetId}/upload/part-urls", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MediaUploadService.UploadView> partUrls(@AuthenticationPrincipal Jwt identity,
                                                            @PathVariable String assetId,
                                                            InputStream body) {
        var request = MediaUploadCommand.partRange(body);
        return privateOk(uploads.partUrls(actor(identity), id(assetId), request.generation(),
                request.firstPart(), request.count()));
    }

    @PostMapping(value = "/{assetId}/upload/finalize", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MediaUploadService.UploadView> finalizeUpload(@AuthenticationPrincipal Jwt identity,
                                                                  @PathVariable String assetId,
                                                                  InputStream body) {
        var request = MediaUploadCommand.finalizeCommand(body);
        return privateOk(uploads.finalizeUpload(actor(identity), id(assetId), request.generation(),
                request.commandId()));
    }

    @DeleteMapping("/{assetId}/upload")
    ResponseEntity<MediaUploadService.UploadView> cancel(@AuthenticationPrincipal Jwt identity,
                                                          @PathVariable String assetId,
                                                          @RequestParam long generation) {
        return privateOk(uploads.cancel(actor(identity), id(assetId), generation));
    }

    private static <T> ResponseEntity<T> privateOk(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }

    private static UUID actor(Jwt identity) { return id(identity.getSubject()); }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }

}
