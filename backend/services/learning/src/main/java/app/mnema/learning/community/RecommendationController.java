package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckPrecondition;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.api.InvalidRequestException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** Authenticated Recs routes in the existing Learning read/write chain. Switches precede JSON/query validation. */
@RestController
@RequestMapping(value = "/community", produces = MediaType.APPLICATION_JSON_VALUE)
public class RecommendationController {
    private final InterestService interests;
    private final CatalogEvents events;
    private final RecommendationService recommendations;

    RecommendationController(InterestService interests, CatalogEvents events, RecommendationService recommendations) {
        this.interests = interests;
        this.events = events;
        this.recommendations = recommendations;
    }

    @GetMapping("/interests")
    ResponseEntity<JsonNode> interests(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        interests.requireEnabled();
        noQuery(request);
        return answer(interests.profile(account(token)), true);
    }

    @PutMapping(value = "/interests", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> select(@AuthenticationPrincipal Jwt token, HttpServletRequest request, InputStream input) {
        interests.requireEnabled();
        noQuery(request);
        long version = DeckPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH));
        return answer(interests.select(account(token), version, RecommendationCommands.selection(input)), true);
    }

    @PostMapping(value = "/interests:reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> reset(@AuthenticationPrincipal Jwt token, HttpServletRequest request, InputStream input) {
        interests.requireEnabled();
        noQuery(request);
        long version = DeckPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH));
        JsonNode command = RecommendationCommands.read(input, Set.of("commandId"));
        return answer(interests.reset(account(token), version, RecommendationCommands.command(command), command), true);
    }

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> event(@AuthenticationPrincipal Jwt token, HttpServletRequest request, InputStream input) {
        events.requireEnabled();
        noQuery(request);
        events.event(account(token), RecommendationCommands.event(input));
        return empty();
    }

    @PostMapping(value = "/exposures", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> exposures(@AuthenticationPrincipal Jwt token, HttpServletRequest request, InputStream input) {
        events.requireEnabled();
        noQuery(request);
        events.exposure(account(token), RecommendationCommands.exposure(input));
        return empty();
    }

    @GetMapping("/recommendations")
    ResponseEntity<JsonNode> recommendations(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        recommendations.requireEnabled();
        return answer(recommendations.page(account(token), request), false);
    }

    private static UUID account(Jwt token) {
        return UuidPolicy.requireEntityId(UUID.fromString(token.getSubject()), "accountId");
    }

    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new InvalidRequestException();
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        headers.setVary(java.util.List.of(HttpHeaders.AUTHORIZATION));
        return headers;
    }

    private static ResponseEntity<JsonNode> answer(JsonNode body, boolean version) {
        HttpHeaders headers = headers();
        if (version) headers.setETag("\"" + body.path("rowVersion").stringValue(null) + "\"");
        return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<Void> empty() {
        return ResponseEntity.noContent().headers(headers()).build();
    }
}
