package app.mnema.learning.community;

import app.mnema.learning.admin.AdminConsoleAccess;
import app.mnema.learning.admin.AdminReportRange;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * The owner console's anomaly routes ({@code GET /api/admin/community/anomalies?cursor=} and {@code POST /api/admin/community/anomalies/{code}:dismiss}, contract
 * {@code contracts/community/catalog.json}, {@code anomalies}). Every route requires the configured console owner AND an access token of the admin client
 * ({@link AdminConsoleAccess}, a 403 for anyone else, before anything is read); the private chain gives {@code learning.read} to GET and {@code learning.write} to the dismissal. Every
 * answer is {@code private, no-store}; the query accepts {@code cursor} only (an unknown or duplicate parameter is 400); the dismissal body is exactly {@code {commandId}}.
 * These routes do not depend on the catalogue kill switch: the console keeps working while the public routes are off.
 */
@RestController
@RequestMapping("/admin/community/anomalies")
public class AnomalyAdminController {
    private static final int MAX_BODY = 512;
    private static final ContentJsonReader BODY = new ContentJsonReader(MAX_BODY, 2, 4);

    private final AdminConsoleAccess access;
    private final AnomalyAdminService service;

    AnomalyAdminController(AdminConsoleAccess access, AnomalyAdminService service) {
        this.access = access;
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        access.require(token);
        AdminReportRange.query(request, Set.of("cursor"));
        return ResponseEntity.ok().headers(privateHeaders()).contentType(MediaType.APPLICATION_JSON).body(service.list(request.getParameter("cursor")));
    }

    @PostMapping(value = "/{code}:dismiss", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> dismiss(@AuthenticationPrincipal Jwt token, @PathVariable String code, InputStream body) {
        UUID actor = access.require(token);
        service.dismiss(actor, code, commandId(body));
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    private static UUID commandId(InputStream input) {
        try {
            JsonNode body = BODY.read(input.readNBytes(MAX_BODY + 1));
            if (!body.isObject() || body.size() != 1 || !body.path("commandId").isString()) throw new InvalidRequestException();
            String text = body.path("commandId").stringValue(null);
            if (text.length() != 36) throw new InvalidRequestException();
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new InvalidRequestException();
            return UuidPolicy.requireCommandId(id);
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw new InvalidRequestException();
        }
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }
}
