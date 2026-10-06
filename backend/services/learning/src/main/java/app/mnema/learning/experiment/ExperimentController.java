package app.mnema.learning.experiment;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.promo.PromoBodies;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Set;

/**
 * {@code POST /api/experiment-events {key, event: EXPOSURE|CONVERSION}} → 204. The event is counted in a metric against the variant the server
 * assigned to the token's account; an experiment that is not enabled is accepted and ignored, a malformed body is a 400.
 */
@RestController
@RequestMapping(value = "/experiment-events", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ExperimentController {
    private final ExperimentEvents events;

    ExperimentController(ExperimentEvents events) {
        this.events = events;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> record(@AuthenticationPrincipal Jwt identity, InputStream body) {
        JsonNode command = PromoBodies.object(body, Set.of("key", "event"), Set.of("key", "event"));
        String key = PromoBodies.text(command, "key");
        ExperimentEvents.Event event;
        try {
            event = ExperimentEvents.Event.valueOf(PromoBodies.text(command, "event"));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new InvalidRequestException();
        }
        if (key == null || key.length() > 40) throw new InvalidRequestException();
        events.record(PromoBodies.owner(identity), key, event);
        return ResponseEntity.noContent().header("Cache-Control", "private, no-store").build();
    }
}
