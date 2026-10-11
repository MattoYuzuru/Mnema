package app.mnema.learning.library;

import app.mnema.learning.library.AccessRepository.Grant;
import app.mnema.learning.library.AccessRepository.Request;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The owner's side of access to an invitation deck (contract {@code contracts/decks/access.json}): invitation links, the requests for access and the people
 * with access, all under {@code /api/decks/{deckId}}. Authentication, scopes ({@code learning.read} for GET, {@code learning.write} for the rest) and the
 * current Identity check are the private chain's; every service checks the owner BEFORE anything else, so a foreign deck is the opaque 404. Answers are
 * {@code private, no-store} (the creation answer carries the one-time token).
 */
@RestController
@RequestMapping(value = "/decks/{deckId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeckAccessController {
    private final InviteService invites;
    private final AccessRequestService requests;
    private final GrantService grants;

    DeckAccessController(InviteService invites, AccessRequestService requests, GrantService grants) {
        this.invites = invites;
        this.requests = requests;
        this.grants = grants;
    }

    // ---- invitation links ----

    @PostMapping(value = "/invites", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> createInvite(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, InputStream body) {
        AccessCommands.InviteSpec spec = AccessCommands.invite(body);
        InviteService.Created created = invites.create(entityId(identity.getSubject()), entityId(deckId), spec.maxUses(), spec.expiresInDays());
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.set("invite", invite(created.invite()));
        answer.put("token", created.token()).put("link", created.link());
        return ResponseEntity.status(HttpStatus.CREATED).headers(privateHeaders()).body(answer);
    }

    @GetMapping("/invites")
    ResponseEntity<JsonNode> listInvites(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, HttpServletRequest request) {
        InviteService.Page page = invites.list(entityId(identity.getSubject()), entityId(deckId), parameter(request, "limit"), parameter(request, "cursor"));
        return page(page.items(), page.nextCursor(), this::invite);
    }

    @PostMapping("/invites/{inviteId}:revoke")
    ResponseEntity<JsonNode> revokeInvite(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String inviteId) {
        return ok(invite(invites.revoke(entityId(identity.getSubject()), entityId(deckId), entityId(inviteId))));
    }

    // ---- requests for access ----

    @GetMapping("/access-requests")
    ResponseEntity<JsonNode> listRequests(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, HttpServletRequest request) {
        AccessRequestService.Page page = requests.list(entityId(identity.getSubject()), entityId(deckId), parameter(request, "limit"), parameter(request, "cursor"));
        return page(page.items(), page.nextCursor(), (Request item) ->
                JsonNodeFactory.instance.objectNode().put("requesterId", item.requesterId().toString()).put("createdAt", item.createdAt().toString()));
    }

    @PostMapping("/access-requests/{requesterId}:approve")
    ResponseEntity<JsonNode> approve(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String requesterId) {
        return ok(decision(requests.approve(entityId(identity.getSubject()), entityId(deckId), entityId(requesterId))));
    }

    @PostMapping("/access-requests/{requesterId}:reject")
    ResponseEntity<JsonNode> reject(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String requesterId) {
        return ok(decision(requests.reject(entityId(identity.getSubject()), entityId(deckId), entityId(requesterId))));
    }

    // ---- people with access ----

    @GetMapping("/grants")
    ResponseEntity<JsonNode> listGrants(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, HttpServletRequest request) {
        GrantService.Page page = grants.list(entityId(identity.getSubject()), entityId(deckId), parameter(request, "limit"), parameter(request, "cursor"));
        return page(page.items(), page.nextCursor(), this::grant);
    }

    @PostMapping(value = "/grants", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> addGrant(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, InputStream body) {
        String username = AccessCommands.username(body);
        return ok(grant(grants.grantByLogin(entityId(identity.getSubject()), entityId(deckId), username)));
    }

    @DeleteMapping("/grants/{granteeId}")
    ResponseEntity<Void> revokeGrant(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String granteeId) {
        grants.revoke(entityId(identity.getSubject()), entityId(deckId), entityId(granteeId));
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    // ---- shapes ----

    private ObjectNode invite(Invite invite) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("inviteId", invite.inviteId().toString()).put("status", invites.status(invite).name()).put("maxUses", invite.maxUses());
        node.put("uses", invite.uses()).put("expiresAt", invite.expiresAt() == null ? null : invite.expiresAt().toString());
        node.put("revokedAt", invite.revokedAt() == null ? null : invite.revokedAt().toString()).put("createdAt", invite.createdAt().toString());
        return node;
    }

    private ObjectNode grant(Grant grant) {
        return JsonNodeFactory.instance.objectNode().put("granteeId", grant.granteeId().toString()).put("role", grant.role().name())
                .put("grantedAt", grant.grantedAt().toString());
    }

    private static ObjectNode decision(AccessRequestService.Decision decision) {
        return JsonNodeFactory.instance.objectNode().put("requesterId", decision.requesterId().toString()).put("status", decision.status())
                .put("decidedAt", decision.decidedAt().toString());
    }

    private static <T> ResponseEntity<JsonNode> page(List<T> items, String next, Function<T, ObjectNode> shape) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        var array = body.putArray("items");
        items.forEach(item -> array.add(shape.apply(item)));
        body.put("nextCursor", next);
        return ok(body);
    }

    private static ResponseEntity<JsonNode> ok(JsonNode body) { return ResponseEntity.ok().headers(privateHeaders()).body(body); }

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

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }
}
