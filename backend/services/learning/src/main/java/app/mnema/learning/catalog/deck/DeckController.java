package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;

/** Canonical Learning API; authentication and current Identity checks remain platform-owned. */
@RestController
@RequestMapping(value = "/decks", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeckController {
    private final DeckService service;

    public DeckController(DeckService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        return ResponseEntity.ok().headers(privateHeaders()).body(service.list(DeckCommand.entityId(identity.getSubject()),
                parameter(request, "limit"), parameter(request, "cursor")));
    }

    @GetMapping("/{deckId}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId) {
        JsonNode deck = service.read(DeckCommand.entityId(identity.getSubject()), DeckCommand.entityId(deckId));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(deck.path("rowVersion").textValue()).body(deck);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, InputStream body, HttpServletRequest request) {
        var result = service.create(DeckCommand.entityId(identity.getSubject()), DeckCommand.read(body));
        var response = write(result, HttpStatus.CREATED);
        response.location(URI.create(request.getContextPath() + "/decks/" + result.acknowledgement().path("deck").path("deckId").textValue()));
        return response.body(result.acknowledgement());
    }

    @PatchMapping(value = "/{deckId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> save(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  InputStream body, HttpServletRequest request) {
        var result = service.save(DeckCommand.entityId(identity.getSubject()), DeckCommand.entityId(deckId),
                DeckPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)), DeckCommand.read(body));
        return write(result, HttpStatus.OK).body(result.acknowledgement());
    }

    private static ResponseEntity.BodyBuilder write(DeckService.WriteResult result, HttpStatus status) {
        var response = ResponseEntity.status(status).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(result.acknowledgement().path("deck").path("rowVersion").textValue());
        return response;
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }
}
