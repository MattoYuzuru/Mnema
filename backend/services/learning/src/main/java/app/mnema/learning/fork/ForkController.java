package app.mnema.learning.fork;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/**
 * «Добавить к себе» ({@code /api/decks/forks}, {@code /api/decks/{deckId}/fork:retry}, {@code fork:detach}; contract {@code contracts/decks/forks.json}). Private chain: the
 * authenticated subject is the only owner input and {@code learning.write} is needed for all three. The creation route is disabled (404) unless
 * {@code learning.community.forks.enabled}. Answers are {@code private, no-store}.
 */
@RestController
@RequestMapping(value = "/decks", produces = MediaType.APPLICATION_JSON_VALUE)
public class ForkController {
    private final ForkService service;

    ForkController(ForkService service) { this.service = service; }

    @PostMapping(value = "/forks", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, InputStream body, HttpServletRequest request) {
        service.requireEnabled();
        ForkService.WriteResult result = service.create(entityId(identity.getSubject()), ForkCommand.read(body));
        var response = ResponseEntity.status(result.existing() ? HttpStatus.OK : HttpStatus.CREATED).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        if (!result.existing()) {
            response.location(URI.create(request.getContextPath() + "/decks/" + result.acknowledgement().path("deckId").stringValue(null)));
        }
        return response.body(result.acknowledgement());
    }

    @PostMapping("/{deckId}/fork:retry")
    ResponseEntity<JsonNode> retry(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId) {
        return ResponseEntity.ok().headers(privateHeaders()).body(service.retry(entityId(identity.getSubject()), entityId(deckId)));
    }

    @PostMapping("/{deckId}/fork:detach")
    ResponseEntity<JsonNode> detach(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId) {
        return ResponseEntity.ok().headers(privateHeaders()).body(service.detach(entityId(identity.getSubject()), entityId(deckId)));
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
