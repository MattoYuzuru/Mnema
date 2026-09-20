package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/** Private owner-scoped boundary for bounded Study session snapshots. */
@RestController
@RequestMapping(value = "/decks/{deckId}/study-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class StudySessionController {
    private final StudySessionService service;

    public StudySessionController(StudySessionService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> start(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                   InputStream body) {
        UUID deck = id(deckId);
        StudySessionService.StartResult result = service.start(id(identity.getSubject()), deck,
                identity.getClaimAsString("zoneinfo"), StudySessionCommand.read(body));
        HttpStatus status = result.preparing() ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        String sessionId = result.body().path("sessionId").textValue();
        response.location(URI.create("/api/decks/" + deck + "/study-sessions/" + sessionId));
        return response.body(result.body());
    }

    @GetMapping("/{sessionId}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @PathVariable String sessionId) {
        return ResponseEntity.ok().headers(privateHeaders())
                .body(service.read(id(identity.getSubject()), id(deckId), id(sessionId)));
    }

    @GetMapping("/replay-sources")
    ResponseEntity<JsonNode> replaySources(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId) {
        return ResponseEntity.ok().headers(privateHeaders()).body(service.replaySources(
                id(identity.getSubject()), id(deckId), identity.getClaimAsString("zoneinfo")));
    }

    @PostMapping("/{sessionId}/presentations")
    ResponseEntity<JsonNode> presentations(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                           @PathVariable String sessionId) {
        return ResponseEntity.ok().headers(privateHeaders())
                .body(service.presentations(id(identity.getSubject()), id(deckId), id(sessionId)));
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException exception) { throw new InvalidRequestException(); }
    }
}
