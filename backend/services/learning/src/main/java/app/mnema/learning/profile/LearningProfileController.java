package app.mnema.learning.profile;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * The owner's goal answer. Reading needs {@code learning.read}, writing {@code learning.write}: the blanket rule of the
 * security chain. The owner is the token subject.
 */
@RestController
@RequestMapping(value = "/learning-profile", produces = MediaType.APPLICATION_JSON_VALUE)
public final class LearningProfileController {
    private static final int MAX_BODY_BYTES = 256;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_BODY_BYTES, 2, 8);

    private final LearningProfileService profile;

    LearningProfileController(LearningProfileService profile) {
        this.profile = profile;
    }

    @GetMapping
    ResponseEntity<LearningProfileView> read(@AuthenticationPrincipal Jwt identity) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(profile.read(owner(identity)));
    }

    /** Exactly {@code {"goal": "<GOAL>"}} or {@code {"goal": null, "skipped": true}}. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LearningProfileView> answer(@AuthenticationPrincipal Jwt identity, InputStream body) {
        UUID owner = owner(identity);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(profile.answer(owner, goal(body)));
    }

    private static LearningGoal goal(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_BODY_BYTES + 1));
            if (!body.isObject() || !body.has("goal")) throw new InvalidRequestException();
            JsonNode goal = body.path("goal");
            if (goal.isNull()) {
                if (body.size() != 2 || !body.path("skipped").isBoolean() || !body.path("skipped").booleanValue()) {
                    throw new InvalidRequestException();
                }
                return null;
            }
            if (body.size() != 1 || !goal.isString()) throw new InvalidRequestException();
            return LearningGoal.valueOf(goal.stringValue(null));
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static UUID owner(Jwt identity) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(identity.getSubject()), "owner");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
