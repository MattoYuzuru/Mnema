package app.mnema.learning.notification;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * The owner's notification center ({@code contracts/notifications}). Reading needs {@code learning.read}, the other
 * methods {@code learning.write}: the blanket rule of the security chain, not a per-route exception.
 */
@RestController
@RequestMapping(value = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
public final class NotificationController {
    private static final int MAX_BODY_BYTES = 256;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_BODY_BYTES, 2, 8);

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    ResponseEntity<NotificationService.Page> list(
            @AuthenticationPrincipal Jwt identity,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String condition) {
        if (after != null && cursor != null) throw new InvalidRequestException();
        var listing = notifications.list(actor(identity), NotificationCursor.pageSize(limit),
                after == null ? null : NotificationCursor.parseSeq(after), NotificationCursor.decode(cursor), condition);
        var response = ResponseEntity.status(listing.page() == null ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header(HttpHeaders.ETAG, listing.etag());
        return listing.page() == null ? response.build() : response.body(listing.page());
    }

    @PutMapping(value = "/read-cursor", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationService.ReadCursor> setReadCursor(@AuthenticationPrincipal Jwt identity,
                                                                 InputStream body) {
        long readUpto = NotificationCursor.parseSeq(readUpto(body));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(notifications.advanceReadCursor(actor(identity), readUpto));
    }

    @DeleteMapping("/{notificationId}")
    ResponseEntity<Void> dismiss(@AuthenticationPrincipal Jwt identity, @PathVariable String notificationId) {
        notifications.dismiss(actor(identity), id(notificationId));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }

    /** Exactly {@code {"readUpto": "<decimal seq>"}}: the sequence is a string on the wire, like every {@code seq}. */
    private static String readUpto(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_BODY_BYTES + 1));
            if (body.size() != 1 || !body.path("readUpto").isString()) throw new InvalidRequestException();
            return body.path("readUpto").stringValue(null);
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static UUID actor(Jwt identity) { return id(identity.getSubject()); }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }
}
