package app.mnema.learning.usage;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * {@code POST /api/decks/{deckId}/generation-estimates}: preflight cost of a spec or of one edit. A POST that only
 * reads, so it needs {@code learning.write} like every non-GET and carries no {@code commandId}: it is a pure function
 * of the body, the rate card and the current balance.
 */
@RestController
@RequestMapping(value = "/decks/{deckId}/generation-estimates", produces = MediaType.APPLICATION_JSON_VALUE)
public final class EstimateController {
    private final EstimateService estimates;

    EstimateController(EstimateService estimates) {
        this.estimates = estimates;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EstimateView> estimate(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                          InputStream body) {
        UUID owner = UsageController.owner(identity);
        UUID deck = deck(deckId);
        // Read the (bounded) bytes here, before any transaction: a slow client must not hold a connection.
        byte[] raw = read(body);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(estimates.estimate(owner, deck, raw));
    }

    private static byte[] read(InputStream input) {
        try {
            return input.readNBytes(EstimateService.MAX_BODY_BYTES + 1);
        } catch (IOException failure) {
            throw new InvalidRequestException();
        }
    }

    private static UUID deck(String value) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(value), "deckId");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
