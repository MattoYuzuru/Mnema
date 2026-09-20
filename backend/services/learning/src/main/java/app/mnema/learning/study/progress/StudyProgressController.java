package app.mnema.learning.study.progress;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Owner-scoped explainable material progress without a synthetic mastery percentage. */
@RestController
@RequestMapping(value = "/decks/{deckId}/study-progress", produces = MediaType.APPLICATION_JSON_VALUE)
public class StudyProgressController {
    private final StudyProgressService service;

    public StudyProgressController(StudyProgressService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(headers)
                .body(service.read(id(identity.getSubject()), id(deckId), limit, cursor));
    }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException exception) { throw new InvalidRequestException(); }
    }
}
