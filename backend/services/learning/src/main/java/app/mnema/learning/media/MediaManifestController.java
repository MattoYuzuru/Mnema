package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Owner-only offline inventory; binary URLs are requested separately and expire. */
@RestController
@RequestMapping(value = "/decks/{deckId}/media-manifests", produces = MediaType.APPLICATION_JSON_VALUE)
public final class MediaManifestController {
    private final MediaManifestCatalog catalog;

    MediaManifestController(MediaManifestCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/current")
    ResponseEntity<String> current(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                   @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String condition) {
        return reply(catalog.current(id(identity.getSubject()), id(deckId)), condition);
    }

    @GetMapping("/{manifestId}")
    ResponseEntity<String> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                @PathVariable String manifestId,
                                @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String condition) {
        return reply(catalog.read(id(identity.getSubject()), id(deckId), id(manifestId)), condition);
    }

    private static ResponseEntity<String> reply(MediaManifestCatalog.Snapshot snapshot, String condition) {
        var response = ResponseEntity.status(snapshot.etag().equals(condition) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, snapshot.etag())
                .header("X-Manifest-Expires-At", snapshot.expiresAt().toString());
        if (snapshot.etag().equals(condition)) return response.build();
        return response.body(snapshot.body());
    }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }
}
