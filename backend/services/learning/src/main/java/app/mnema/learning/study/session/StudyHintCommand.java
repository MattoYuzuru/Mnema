package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;

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
                    || !value.path("nonce").isTextual() || value.path("nonce").textValue().length() < 16
                    || value.path("nonce").textValue().length() > 100
                    || !value.path("blankId").isTextual() || value.path("blankId").textValue().length() != 36) {
                throw new InvalidRequestException();
            }
            UUID blankId = UuidPolicy.requireEntityId(UUID.fromString(value.path("blankId").textValue()), "blankId");
            if (!blankId.toString().equals(value.path("blankId").textValue())) throw new InvalidRequestException();
            return new StudyHintCommand(value.path("nonce").textValue(), blankId);
        } catch (IOException | IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }
}
