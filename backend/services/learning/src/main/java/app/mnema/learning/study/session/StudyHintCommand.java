package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** Strict first-letter hint request: the nonce binds it to one issued presentation, the id to one blank. */
public record StudyHintCommand(String nonce, UUID blankId) {
    private static final int MAX_BYTES = 512;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 3, 16);

    public static StudyHintCommand read(InputStream input) {
        try {
            JsonNode value = JSON.read(input.readNBytes(MAX_BYTES + 1));
            if (!value.isObject() || !value.properties().stream().map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet()).equals(Set.of("nonce", "blankId"))
                    || !value.path("nonce").isString() || value.path("nonce").stringValue(null).length() < 16
                    || value.path("nonce").stringValue(null).length() > 100
                    || !value.path("blankId").isString() || value.path("blankId").stringValue(null).length() != 36) {
                throw new InvalidRequestException();
            }
            UUID blankId = UuidPolicy.requireEntityId(UUID.fromString(value.path("blankId").stringValue(null)), "blankId");
            if (!blankId.toString().equals(value.path("blankId").stringValue(null))) throw new InvalidRequestException();
            return new StudyHintCommand(value.path("nonce").stringValue(null), blankId);
        } catch (IOException | IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }
}
