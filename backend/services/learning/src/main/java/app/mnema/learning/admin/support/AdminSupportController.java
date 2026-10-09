package app.mnema.learning.admin.support;

import app.mnema.learning.admin.AdminConsoleAccess;
import jakarta.servlet.http.HttpServletRequest;
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

import java.io.InputStream;
import java.util.UUID;

/** Owner authentication precedes every bridge operation; browsers never see its credential. */
@RestController
@RequestMapping(value = "/admin/support/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
public final class AdminSupportController {
    private final AdminConsoleAccess access;
    private final AdminSupportClient client;

    AdminSupportController(AdminConsoleAccess access, AdminSupportClient client) {
        this.access = access;
        this.client = client;
    }

    @GetMapping
    ResponseEntity<JsonNode> tickets(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        actor(identity);
        return response(client.get("/tickets" + SupportRequests.query(request, false)));
    }

    @GetMapping("/{ticketId}")
    ResponseEntity<JsonNode> conversation(@AuthenticationPrincipal Jwt identity, @PathVariable String ticketId,
                                         HttpServletRequest request) {
        actor(identity);
        return response(client.get("/tickets/" + SupportRequests.numeric(ticketId) + SupportRequests.query(request, true)));
    }

    @PostMapping(value = "/{ticketId}/commands", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> command(@AuthenticationPrincipal Jwt identity, @PathVariable String ticketId, InputStream body) {
        UUID actor = actor(identity);
        return response(client.command("/tickets/" + SupportRequests.numeric(ticketId) + "/commands",
                SupportRequests.command(body, actor)));
    }

    private UUID actor(Jwt identity) {
        UUID actor = SupportRequests.uuid(identity.getSubject(), false);
        access.require(actor);
        return actor;
    }

    private static ResponseEntity<JsonNode> response(AdminSupportClient.Result result) {
        var response = ResponseEntity.status(result.status()).header("Cache-Control", "private, no-store");
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(result.body());
    }
}
