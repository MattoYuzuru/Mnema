package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** A pair interaction never supplies assessment authority or modifies progress. */
public record PairCheckCommand(UUID presentationId, String nonce, UUID leftId, UUID rightId) {
    private static final int MAX_BYTES = 1_024;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 4, 32);

    public static PairCheckCommand read(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            if (!body.isObject() || !body.properties().stream().map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("presentationId", "nonce", "leftId", "rightId"))
                    || !body.path("nonce").isString() || body.path("nonce").stringValue(null).length() < 16
                    || body.path("nonce").stringValue(null).length() > 100) throw new InvalidRequestException();
            return new PairCheckCommand(id(body.path("presentationId")), body.path("nonce").stringValue(null),
                    id(body.path("leftId")), id(body.path("rightId")));
        } catch (IOException | IllegalArgumentException exception) { throw new InvalidRequestException(); }
    }

    private static UUID id(JsonNode value) {
        if (!value.isString() || value.stringValue(null).length() != 36) throw new InvalidRequestException();
        UUID id = UuidPolicy.requireEntityId(UUID.fromString(value.stringValue(null)), "id");
        if (!id.toString().equals(value.stringValue(null))) throw new InvalidRequestException();
        return id;
    }
}
