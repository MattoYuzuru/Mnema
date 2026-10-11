package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckPrecondition;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.security.IdentityClaims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.UUID;

/**
 * The owner's publication of a deck ({@code /api/decks/{deckId}/publication}, contract {@code contracts/decks/publication.json}) and the topic directory.
 * Authentication, scopes ({@code learning.read} for GET, {@code learning.write} for PUT) and the current Identity check are the private chain's; the
 * answers are {@code private, no-store} and a foreign deck is the opaque 404.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicationController {
    private final PublicationService service;
    private final TopicDirectory topics;

    PublicationController(PublicationService service, TopicDirectory topics) {
        this.service = service;
        this.topics = topics;
    }

    @GetMapping("/decks/{deckId}/publication")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, HttpServletRequest request) {
        JsonNode state = service.read(entityId(identity.getSubject()), entityId(deckId), IdentityClaims.publicProfileReady(request));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(state.path("rowVersion").stringValue(null)).body(state);
    }

    @PutMapping(value = "/decks/{deckId}/publication", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> save(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, InputStream body, HttpServletRequest request) {
        UUID owner = entityId(identity.getSubject());
        UUID deck = entityId(deckId);
        long expected = DeckPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH));
        PublicationService.WriteResult result = service.save(owner, deck, expected, PublicationCommand.read(body), IdentityClaims.publicProfileReady(request));
        var response = ResponseEntity.ok().headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(result.acknowledgement().path("publication").path("rowVersion").stringValue(null));
        return response.body(result.acknowledgement());
    }

    /** The directory is static data and the same for everybody who is signed in: a client may keep it for an hour. */
    @GetMapping("/topics")
    ResponseEntity<JsonNode> topics() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, max-age=3600");
        return ResponseEntity.ok().headers(headers).body(topics.tree());
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }

    private static UUID entityId(String value) {
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
