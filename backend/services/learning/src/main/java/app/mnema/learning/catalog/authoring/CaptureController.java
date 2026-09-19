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
@RequestMapping(value = "/capture-notes", produces = MediaType.APPLICATION_JSON_VALUE)
public class CaptureController {
    private final CaptureService service;

    public CaptureController(CaptureService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        return ResponseEntity.ok().headers(privateHeaders()).body(service.list(actor(identity),
                parameter(request, "limit"), parameter(request, "cursor")));
    }

    @GetMapping("/{noteId}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String noteId) {
        JsonNode result = service.read(actor(identity), AuthoringIds.entity(noteId));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(result.path("rowVersion").textValue()).body(result);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, InputStream body,
                                    HttpServletRequest request) {
        CaptureService.WriteResult result = service.create(actor(identity), AuthoringCommands.captureCreate(body));
        var response = write(result, HttpStatus.CREATED,
                result.acknowledgement().path("capture").path("rowVersion").textValue());
        response.location(URI.create(request.getContextPath() + "/capture-notes/"
                + result.acknowledgement().path("capture").path("noteId").textValue()));
        return response.body(result.acknowledgement());
    }

    @PutMapping(value = "/{noteId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> update(@AuthenticationPrincipal Jwt identity, @PathVariable String noteId,
                                    InputStream body, HttpServletRequest request) {
        JsonNode result = service.update(actor(identity), AuthoringIds.entity(noteId),
                AuthoringPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)),
                AuthoringCommands.captureUpdate(body));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(result.path("rowVersion").textValue()).body(result);
    }

    @PostMapping(value = "/{noteId}/archive", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> archive(@AuthenticationPrincipal Jwt identity, @PathVariable String noteId,
                                     InputStream body, HttpServletRequest request) {
        JsonNode result = service.archive(actor(identity), AuthoringIds.entity(noteId),
                AuthoringPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)),
                AuthoringCommands.captureArchive(body));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(result.path("rowVersion").textValue()).body(result);
    }

    @PostMapping(value = "/{noteId}/conversions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> convert(@AuthenticationPrincipal Jwt identity, @PathVariable String noteId,
                                     InputStream body, HttpServletRequest request) {
        CaptureService.WriteResult result = service.convert(actor(identity), AuthoringIds.entity(noteId),
                AuthoringPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)),
                AuthoringCommands.captureConvert(body));
        return write(result, HttpStatus.OK, result.acknowledgement().path("noteVersion").textValue())
                .body(result.acknowledgement());
    }

    @DeleteMapping("/{noteId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt identity, @PathVariable String noteId,
                                HttpServletRequest request) {
        service.delete(actor(identity), AuthoringIds.entity(noteId),
                AuthoringPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)));
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    private static ResponseEntity.BodyBuilder write(CaptureService.WriteResult result, HttpStatus status, String version) {
        var response = ResponseEntity.status(status).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(version);
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
