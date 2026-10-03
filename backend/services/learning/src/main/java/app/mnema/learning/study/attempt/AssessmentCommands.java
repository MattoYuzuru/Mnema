package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Strict readers of the small bodies of the assessment commands; every failure is the opaque {@code INVALID_REQUEST}. */
final class AssessmentCommands {
    private static final int MAX_BYTES = 4_096;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 4, 100);

    private AssessmentCommands() { }

    /** {@code POST .../self-check}: no body, or the empty object. */
    static void readEmpty(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || new String(bytes, StandardCharsets.UTF_8).isBlank()) return;
            AttemptCommand.fields(JSON.read(bytes), Set.of());
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    /** {@code POST .../self-rating}: {@code {rating}} with the rating of a plain self-check. */
    static AttemptCommand.SelfRating readRating(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            AttemptCommand.fields(body, Set.of("rating"));
            if (!body.path("rating").isString()) throw new InvalidRequestException();
            return AttemptCommand.SelfRating.valueOf(body.path("rating").stringValue(null));
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    /** {@code POST .../dispute}: {@code {commandId, shareExample?}}. */
    static AssessmentService.DisputeCommand readDispute(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            if (!body.isObject()) throw new InvalidRequestException();
            AttemptCommand.fields(body, body.has("shareExample") ? Set.of("commandId", "shareExample") : Set.of("commandId"));
            boolean share = false;
            if (body.has("shareExample")) {
                if (!body.path("shareExample").isBoolean()) throw new InvalidRequestException();
                share = body.path("shareExample").booleanValue();
            }
            return new AssessmentService.DisputeCommand(AttemptCommand.id(body.path("commandId"), true), share);
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }
}
