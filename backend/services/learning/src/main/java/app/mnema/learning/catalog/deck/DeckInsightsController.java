package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** {@code GET /api/decks/{deckId}/insights}: private/no-store Deck hub statistics. Takes no parameters. */
@RestController
@RequestMapping(value = "/decks/{deckId}/insights", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeckInsightsController {
    private final DeckInsightsService service;

    public DeckInsightsController(DeckInsightsService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new InvalidRequestException();
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(headers).body(service.read(DeckCommand.entityId(identity.getSubject()),
                DeckCommand.entityId(deckId), identity.getClaimAsString("zoneinfo")));
    }
}
