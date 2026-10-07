package app.mnema.learning.events;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/** Every editorial request passes current Identity liveness, Learning scope, then the configured exact owner. */
@RestController
@RequestMapping(value = "/admin/events", produces = MediaType.APPLICATION_JSON_VALUE)
public final class AdminEventsController {
    private final EventService events;
    private final EventAdminAccess access;

    AdminEventsController(EventService events, EventAdminAccess access) {
        this.events = events;
        this.access = access;
    }

    @GetMapping("/access")
    ResponseEntity<Map<String, Boolean>> access(@AuthenticationPrincipal Jwt identity) {
        actor(identity);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(Map.of("allowed", true));
    }

    @GetMapping
    ResponseEntity<JsonNode> page(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        UUID actor = actor(identity);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(events.adminPage(actor, EventRequests.parameter(request, "cursor")));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, InputStream body, HttpServletRequest request) {
        UUID actor = actor(identity);
        var result = events.create(actor, EventRequests.command(body));
        return write(result, HttpStatus.CREATED).location(URI.create(request.getContextPath() + "/admin/events/"
                + result.acknowledgement().path("event").path("eventId").stringValue(null))).body(result.acknowledgement());
    }

    @PutMapping(value = "/{eventId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> replace(@AuthenticationPrincipal Jwt identity, @PathVariable String eventId,
                                     InputStream body, HttpServletRequest request) {
        UUID actor = actor(identity);
        var result = events.replace(actor, EventRequests.entityId(eventId),
                EventRequests.version(request.getHeaders(HttpHeaders.IF_MATCH)), EventRequests.command(body));
        return write(result, HttpStatus.OK).body(result.acknowledgement());
    }

    @DeleteMapping("/{eventId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt identity, @PathVariable String eventId, HttpServletRequest request) {
        UUID actor = actor(identity);
        var result = events.delete(actor, EventRequests.entityId(eventId), EventRequests.version(request.getHeaders(HttpHeaders.IF_MATCH)),
                EventRequests.commandId(EventRequests.parameter(request, "commandId")));
        var response = ResponseEntity.noContent().header("Cache-Control", "private, no-store");
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.build();
    }

    private UUID actor(Jwt identity) {
        UUID actor = EventRequests.entityId(identity.getSubject());
        access.require(actor);
        return actor;
    }

    private static ResponseEntity.BodyBuilder write(EventService.WriteResult result, HttpStatus status) {
        var response = ResponseEntity.status(status).header("Cache-Control", "private, no-store");
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(result.acknowledgement().path("event").path("rowVersion").stringValue(null));
        return response;
    }
}
