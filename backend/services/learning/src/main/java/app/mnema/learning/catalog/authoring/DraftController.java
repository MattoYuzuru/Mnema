package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(value = "/editing-drafts", produces = MediaType.APPLICATION_JSON_VALUE)
public class DraftController {
    private final DraftService service;

    public DraftController(DraftService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        return ResponseEntity.ok().headers(privateHeaders()).body(service.list(actor(identity),
                parameter(request, "limit"), parameter(request, "cursor")));
    }

    @GetMapping("/{draftId}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String draftId) {
        JsonNode result = service.read(actor(identity), AuthoringIds.entity(draftId));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(result.path("rowVersion").textValue()).body(result);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, InputStream body,
                                    HttpServletRequest request) {
        DraftService.WriteResult result = service.create(actor(identity), AuthoringCommands.draftCreate(body));
        var response = write(result, HttpStatus.CREATED);
        response.location(URI.create(request.getContextPath() + "/editing-drafts/"
                + result.acknowledgement().path("draft").path("draftId").textValue()));
        return response.body(result.acknowledgement());
    }

    @PutMapping(value = "/{draftId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> update(@AuthenticationPrincipal Jwt identity, @PathVariable String draftId,
                                    InputStream body, HttpServletRequest request) {
        DraftService.WriteResult result = service.update(actor(identity), AuthoringIds.entity(draftId),
                AuthoringPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)),
                AuthoringCommands.draftUpdate(body));
        return write(result, HttpStatus.OK).body(result.acknowledgement());
    }

    @DeleteMapping("/{draftId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt identity, @PathVariable String draftId,
                                HttpServletRequest request) {
        service.delete(actor(identity), AuthoringIds.entity(draftId),
                AuthoringPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)));
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    private static ResponseEntity.BodyBuilder write(DraftService.WriteResult result, HttpStatus status) {
        var response = ResponseEntity.status(status).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(result.acknowledgement().path("draft").path("rowVersion").textValue());
        return response;
    }

    private static UUID actor(Jwt identity) { return AuthoringIds.entity(identity.getSubject()); }

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
