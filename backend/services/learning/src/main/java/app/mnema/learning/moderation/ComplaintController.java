package app.mnema.learning.moderation;

import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.InputStream;
import java.util.UUID;

/**
 * Filing a complaint ({@code POST /api/public/decks/{code}/complaints} on the public chain, {@code POST /api/public/profiles/{username}/complaints} on the
 * private one) and the owner's notice ({@code GET /api/decks/{deckId}/moderation}); contract {@code contracts/moderation/complaints.json}. Filing is for a
 * signed-in account (the chain gives a guest 401). The kill switch is checked BEFORE the body is read, so a switched-off route is a 404 whatever is sent. The
 * public code of the deck is a credential: it is redacted from the logs of its route like the other public routes.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ComplaintController {
    private final ComplaintService complaints;
    private final DeckModeration decks;

    ComplaintController(ComplaintService complaints, DeckModeration decks) {
        this.complaints = complaints;
        this.decks = decks;
    }

    @PostMapping(value = "/public/decks/{code}/complaints", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> fileDeck(@AuthenticationPrincipal Jwt identity, @PathVariable String code, InputStream body) {
        complaints.requireDeckFiling();
        ComplaintCommands.Filing filing = ComplaintCommands.filing(body);
        complaints.fileDeck(entityId(identity.getSubject()), code, filing);
        return received();
    }

    @PostMapping(value = "/public/profiles/{username}/complaints", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> fileProfile(@AuthenticationPrincipal Jwt identity, @PathVariable String username, InputStream body) {
        complaints.requireProfileFiling();
        ComplaintCommands.Filing filing = ComplaintCommands.filing(body);
        complaints.fileProfile(entityId(identity.getSubject()), username, filing);
        return received();
    }

    /** Whether the deck is hidden and how many of its units are taken down: the owner's notice, the opaque 404 for everybody else. */
    @GetMapping("/decks/{deckId}/moderation")
    ResponseEntity<JsonNode> notice(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId) {
        DeckModeration.Notice notice = decks.notice(entityId(identity.getSubject()), entityId(deckId)).orElseThrow(ResourceNotFoundException::new);
        var body = JsonNodeFactory.instance.objectNode().put("deckId", notice.deckId().toString()).put("hiddenByModeration", notice.hidden())
                .put("hiddenAt", notice.hiddenAt() == null ? null : notice.hiddenAt().toString());
        body.putObject("takenDown").put("materials", notice.materials()).put("exercises", notice.exercises());
        return ResponseEntity.ok().headers(privateHeaders()).body(body);
    }

    private static ResponseEntity<JsonNode> received() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).headers(privateHeaders()).body(JsonNodeFactory.instance.objectNode().put("status", "RECEIVED"));
    }

    static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }

    static UUID entityId(String value) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "id");
            if (!id.toString().equalsIgnoreCase(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
