package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.security.ClientAddresses;
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

import java.util.UUID;

/**
 * Public reads of a deck by its code ({@code /api/public/decks/{code}}, contract {@code contracts/decks/public-read.json}). GET and HEAD only, served
 * by the optional-bearer security chain: no token is a guest, a valid token identifies the account, an invalid token is 401 and never a guest. The
 * routes are disabled (404) unless {@code learning.community.public-routes.enabled} is set. Answers vary with the bearer, so they are not cacheable.
 */
@RestController
@RequestMapping(value = "/public/decks/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
public class PublicDeckController {
    private final PublicDeckService service;
    private final ClientAddresses addresses;

    PublicDeckController(PublicDeckService service, ClientAddresses addresses) {
        this.service = service;
        this.addresses = addresses;
    }

    @GetMapping
    ResponseEntity<JsonNode> summary(@AuthenticationPrincipal Jwt identity, @PathVariable String code, HttpServletRequest request) {
        return ok(service.summary(viewer(identity, request), code));
    }

    @GetMapping("/items")
    ResponseEntity<JsonNode> items(@AuthenticationPrincipal Jwt identity, @PathVariable String code, HttpServletRequest request) {
        return ok(service.items(viewer(identity, request), code, parameter(request, "limit"), parameter(request, "cursor")));
    }

    @GetMapping("/items/{memberKey}")
    ResponseEntity<JsonNode> item(@AuthenticationPrincipal Jwt identity, @PathVariable String code, @PathVariable String memberKey,
                                  HttpServletRequest request) {
        return ok(service.item(viewer(identity, request), code, memberKey));
    }

    @GetMapping("/exercises")
    ResponseEntity<JsonNode> exercises(@AuthenticationPrincipal Jwt identity, @PathVariable String code, HttpServletRequest request) {
        return ok(service.exercises(viewer(identity, request), code, parameter(request, "limit"), parameter(request, "cursor")));
    }

    private Viewer viewer(Jwt identity, HttpServletRequest request) {
        String network = addresses.resolveNetwork(request).orElse(null);
        return identity == null ? Viewer.guest(network, addresses.resolveCoarseNetwork(request).orElse(null)) : Viewer.account(entityId(identity.getSubject()), network);
    }

    private static ResponseEntity<JsonNode> ok(JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setVary(java.util.List.of(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static UUID entityId(String value) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(value), "id");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }
}
