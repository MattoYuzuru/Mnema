package app.mnema.learning.fork;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Strict, bounded body of {@code POST /api/decks/forks}: {@code {commandId, sourceCode}} and nothing else. The code is a credential of a link, so it is
 * only ever carried in the body (never a path or query) and the envelope of the receipt keeps just its digest. A code of any shape is accepted here as a string;
 * a malformed or unknown one is the same 404 as a private deck (resolved by {@code DeckAccess}).
 */
public record ForkCommand(UUID commandId, String sourceCode) {
    public static final int MAX_REQUEST_BYTES = 8192;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_REQUEST_BYTES, 4, 16);

    public ForkCommand {
        try {
            UuidPolicy.requireCommandId(commandId);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new InvalidRequestException();
        }
        if (sourceCode == null || sourceCode.isEmpty() || sourceCode.length() > 64) throw new InvalidRequestException();
    }

    static ForkCommand read(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            if (!body.isObject() || body.size() != 2 || !body.has("commandId") || !body.has("sourceCode")) throw new InvalidRequestException();
            if (!body.path("commandId").isString() || !body.path("sourceCode").isString()) throw new InvalidRequestException();
            String id = body.path("commandId").stringValue(null);
            if (id.length() != 36) throw new InvalidRequestException();
            UUID command = UUID.fromString(id);
            if (!command.toString().equalsIgnoreCase(id)) throw new InvalidRequestException();
            return new ForkCommand(command, body.path("sourceCode").stringValue(null));
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw new InvalidRequestException();
        }
    }

    /** The identity of the command: the actor and type are bound by the receipt, the body here. */
    ObjectNode envelope() {
        return JsonNodeFactory.instance.objectNode().put("sourceCode", sourceCode);
    }
}
