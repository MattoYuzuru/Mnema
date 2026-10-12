package app.mnema.learning.fork;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;

/**
 * Strict, bounded body of {@code PUT /api/decks/{deckId}/fork:auto-update}: {@code {autoUpdate: boolean}} and nothing else. The setting is a state, not a command, so there is no
 * {@code commandId}: repeating the same body is idempotent by itself.
 */
record AutoUpdateCommand(boolean autoUpdate) {
    static final int MAX_REQUEST_BYTES = 256;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_REQUEST_BYTES, 4, 8);

    static AutoUpdateCommand read(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            if (!body.isObject() || body.size() != 1 || !body.path("autoUpdate").isBoolean()) throw new InvalidRequestException();
            return new AutoUpdateCommand(body.path("autoUpdate").booleanValue());
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw new InvalidRequestException();
        }
    }
}
