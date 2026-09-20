package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/** Owner-only authoring boundary for immutable P0 exercise revisions. */
@RestController
@RequestMapping(value = "/decks/{deckId}/exercises", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExerciseController {
    private final ExerciseService service;

    public ExerciseController(ExerciseService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  HttpServletRequest request) {
        JsonNode page = service.list(id(identity.getSubject()), id(deckId), parameter(request, "limit"),
                parameter(request, "cursor"));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(page.path("deckVersion").textValue()).body(page);
    }

    @GetMapping("/{exerciseId}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @PathVariable String exerciseId, HttpServletRequest request) {
        String revision = parameter(request, "revisionId");
        JsonNode value = service.read(id(identity.getSubject()), id(deckId), id(exerciseId),
                revision == null ? null : id(revision));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(value.path("deckVersion").textValue()).body(value);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    InputStream body, HttpServletRequest request) {
        UUID deck = id(deckId);
        ExerciseService.WriteResult result = service.publish(id(identity.getSubject()), deck, null,
                ExercisePrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)), ExerciseCommand.readCreate(body));
        ResponseEntity.BodyBuilder response = write(result, HttpStatus.CREATED);
        response.location(URI.create(request.getContextPath() + "/decks/" + deck + "/exercises/"
                + result.acknowledgement().path("exerciseId").textValue()));
        return response.body(result.acknowledgement());
    }

    @PutMapping(value = "/{exerciseId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> update(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    @PathVariable String exerciseId, InputStream body, HttpServletRequest request) {
        ExerciseService.WriteResult result = service.publish(id(identity.getSubject()), id(deckId), id(exerciseId),
                ExercisePrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)), ExerciseCommand.readUpdate(body));
        return write(result, HttpStatus.OK).body(result.acknowledgement());
    }

    private static ResponseEntity.BodyBuilder write(ExerciseService.WriteResult result, HttpStatus status) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(result.acknowledgement().path("deckVersion").textValue());
        return response;
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException exception) { throw new InvalidRequestException(); }
    }

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }
}
