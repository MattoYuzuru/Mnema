package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
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
@RequestMapping(value = "/decks/{deckId}/study-sessions/{sessionId}/pair-checks",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class PairCheckController {
    private final AttemptService service;

    public PairCheckController(AttemptService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PairCheckResult> check(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                        @PathVariable String sessionId, InputStream body) {
        boolean correct = service.checkPair(id(identity.getSubject()), id(deckId), id(sessionId), PairCheckCommand.read(body));
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(new PairCheckResult(correct));
    }

    public record PairCheckResult(boolean correct) { }

    private static UUID id(String value) {
        try { return UuidPolicy.requireEntityId(UUID.fromString(value), "id"); }
        catch (IllegalArgumentException exception) { throw new InvalidRequestException(); }
    }
}
