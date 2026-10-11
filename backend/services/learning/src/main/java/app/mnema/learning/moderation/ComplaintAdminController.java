package app.mnema.learning.moderation;

import app.mnema.learning.admin.AdminConsoleAccess;
import app.mnema.learning.admin.AdminReportRange;
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
import java.util.Set;
import java.util.UUID;

/**
 * The owner console's complaint routes ({@code /api/admin/complaints}, contract {@code contracts/moderation/complaints.json}). Every route requires the
 * configured console owner AND an access token of the admin client ({@link AdminConsoleAccess}); the private chain gives {@code learning.read} to GET and
 * {@code learning.write} to the decisions. Every answer is {@code private, no-store}. Query parameters are an exact set: an unknown or duplicate one is 400.
 */
@RestController
@RequestMapping(value = "/admin/complaints", produces = MediaType.APPLICATION_JSON_VALUE)
public class ComplaintAdminController {
    private final AdminConsoleAccess access;
    private final ComplaintAdminService service;

    ComplaintAdminController(AdminConsoleAccess access, ComplaintAdminService service) {
        this.access = access;
        this.service = service;
    }

    @GetMapping
    ResponseEntity<JsonNode> queue(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        access.require(token);
        AdminReportRange.query(request, Set.of("status", "limit", "cursor"));
        return ok(service.queue(request.getParameter("status"), request.getParameter("limit"), request.getParameter("cursor")));
    }

    @GetMapping("/journal")
    ResponseEntity<JsonNode> journal(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        access.require(token);
        AdminReportRange.query(request, Set.of("limit", "cursor"));
        return ok(service.journal(request.getParameter("limit"), request.getParameter("cursor")));
    }

    @GetMapping("/{complaintId}")
    ResponseEntity<JsonNode> detail(@AuthenticationPrincipal Jwt token, @PathVariable String complaintId, HttpServletRequest request) {
        access.require(token);
        AdminReportRange.query(request, Set.of());
        return ok(service.detail(ComplaintController.entityId(complaintId)));
    }

    @GetMapping("/{complaintId}/units")
    ResponseEntity<JsonNode> units(@AuthenticationPrincipal Jwt token, @PathVariable String complaintId, HttpServletRequest request) {
        access.require(token);
        AdminReportRange.query(request, Set.of("kind", "limit", "cursor"));
        return ok(service.units(ComplaintController.entityId(complaintId), request.getParameter("kind"), request.getParameter("limit"),
                request.getParameter("cursor")));
    }

    @PostMapping(value = "/{complaintId}:decide", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> decide(@AuthenticationPrincipal Jwt token, @PathVariable String complaintId, InputStream body) {
        UUID actor = access.require(token);
        UUID id = ComplaintController.entityId(complaintId);
        return ok(service.decide(actor, id, ComplaintCommands.decision(body)));
    }

    @PostMapping("/{complaintId}:unhide")
    ResponseEntity<JsonNode> unhide(@AuthenticationPrincipal Jwt token, @PathVariable String complaintId) {
        UUID actor = access.require(token);
        return ok(service.unhide(actor, ComplaintController.entityId(complaintId)));
    }

    private static ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok().headers(ComplaintController.privateHeaders()).body(body);
    }
}
