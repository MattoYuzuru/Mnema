package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import tools.jackson.databind.JsonNode;
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

import java.io.InputStream;
import java.util.UUID;

/**
 * The attempt resource while and after an {@code ai-semantic} answer is assessed: the polled read, «Оценить себя», the
 * learner's own rating and «Оспорить оценку». The submit itself is {@link AttemptController}.
 */
@RestController
@RequestMapping(value = "/decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class AssessmentController {
    private final AssessmentService service;

    public AssessmentController(AssessmentService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @PathVariable String sessionId, @PathVariable String attemptId) {
        return ResponseEntity.ok().headers(privateHeaders())
                .body(service.read(id(identity.getSubject()), id(deckId), id(sessionId), id(attemptId)));
    }

    @PostMapping("/self-check")
    ResponseEntity<JsonNode> selfCheck(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                       @PathVariable String sessionId, @PathVariable String attemptId, InputStream body) {
        AssessmentCommands.readEmpty(body);
        return ResponseEntity.ok().headers(privateHeaders())
                .body(service.selfCheck(id(identity.getSubject()), id(deckId), id(sessionId), id(attemptId)));
    }

    @PostMapping(value = "/self-rating", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> selfRating(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                        @PathVariable String sessionId, @PathVariable String attemptId, InputStream body) {
        return outcome(service.selfRate(id(identity.getSubject()), id(deckId), id(sessionId), id(attemptId),
                AssessmentCommands.readRating(body)));
    }

    @PostMapping(value = "/dispute", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> dispute(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                     @PathVariable String sessionId, @PathVariable String attemptId, InputStream body) {
        return outcome(service.dispute(id(identity.getSubject()), id(deckId), id(sessionId), id(attemptId),
                AssessmentCommands.readDispute(body)));
    }

    private static ResponseEntity<JsonNode> outcome(AttemptService.SubmitResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(result.outcome());
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
