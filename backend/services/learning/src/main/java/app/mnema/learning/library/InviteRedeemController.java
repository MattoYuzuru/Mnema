package app.mnema.learning.library;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.InputStream;

/**
 * Redeeming an invitation link ({@code POST /api/invites:redeem}), asking for access ({@code POST /api/public/decks/{code}/access-requests}) and taking the request back ({@code …:withdraw}); contract
 * {@code contracts/decks/access.json}. Both are done by a signed-in account, and the redeem route (like the two others, whose service checks the same switch) answers 404 unless {@code learning.community.public-routes.enabled}. The token travels in the body of the first, never in a path, a query or a log;
 * the others are on the public chain (so the public code is redacted from its logs like every other path there) and needs a bearer with {@code learning.write}.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class InviteRedeemController {
    private final InviteService invites;
    private final AccessRequestService requests;
    private final PublicRouteSettings routes;

    InviteRedeemController(InviteService invites, AccessRequestService requests, PublicRouteSettings routes) {
        this.invites = invites;
        this.requests = requests;
        this.routes = routes;
    }

    @PostMapping(value = "/invites:redeem", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> redeem(@AuthenticationPrincipal Jwt identity, InputStream body) {
        // The kill switch of the public routes covers this route too (an invitation link leads into the public chain): off is a 404 as if the route did not
        // exist, decided BEFORE the body is read, so a body is neither parsed nor judged.
        if (!routes.enabled) throw new ResourceNotFoundException();
        String token = AccessCommands.token(body);
        String code = invites.redeem(DeckAccessController.entityId(identity.getSubject()), token);
        return ResponseEntity.ok().headers(DeckAccessController.privateHeaders()).body(JsonNodeFactory.instance.objectNode().put("code", code));
    }

    @PostMapping("/public/decks/{code}/access-requests:withdraw")
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt identity, @org.springframework.web.bind.annotation.PathVariable String code) {
        requests.withdraw(DeckAccessController.entityId(identity.getSubject()), code);
        return ResponseEntity.noContent().headers(DeckAccessController.privateHeaders()).build();
    }

    @PostMapping("/public/decks/{code}/access-requests")
    ResponseEntity<JsonNode> ask(@AuthenticationPrincipal Jwt identity, @org.springframework.web.bind.annotation.PathVariable String code) {
        String status = requests.send(DeckAccessController.entityId(identity.getSubject()), code);
        return ResponseEntity.accepted().headers(DeckAccessController.privateHeaders())
                .body(JsonNodeFactory.instance.objectNode().put("status", status));
    }
}
