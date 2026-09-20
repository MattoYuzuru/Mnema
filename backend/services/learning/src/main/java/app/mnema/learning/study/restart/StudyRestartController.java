package app.mnema.learning.study.restart;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping(value = "/decks/{deckId}/study-restarts", produces = MediaType.APPLICATION_JSON_VALUE)
public class StudyRestartController {
    private final StudyRestartService service;

    public StudyRestartController(StudyRestartService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> restart(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                     InputStream body) {
        StudyRestartService.Result result = service.restart(id(identity.getSubject()), id(deckId),
                StudyRestartCommand.read(body));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(result.acknowledgement());
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
}
