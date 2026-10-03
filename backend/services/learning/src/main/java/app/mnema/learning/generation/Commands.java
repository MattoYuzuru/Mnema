package app.mnema.learning.generation;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.UUID;

/** Strict request-body reading shared by the generation commands: bounded, duplicate-free JSON with exact field sets. */
final class Commands {
    /** Request bodies are at most 64 KiB and none of them carries a document. */
    static final int MAX_BODY_BYTES = 65_536;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_BODY_BYTES, 10, 4_096);

    private Commands() { }

    /** @throws InvalidRequestException a body that is not one strict JSON object within the bound */
    static JsonNode read(byte[] raw) {
        try {
            return READER.read(raw);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** The body must have exactly the {@code required} fields and may add {@code optional} ones; others are a 400. */
    static void fields(JsonNode body, Set<String> required, Set<String> optional) {
        for (String name : body.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) throw new InvalidRequestException();
        }
        for (String name : required) {
            if (!body.has(name)) throw new InvalidRequestException();
        }
    }

    /** A canonical lowercase UUIDv4 or UUIDv7 command identifier. */
    static UUID commandId(JsonNode body) {
        return uuid(body, "commandId", true);
    }

    static UUID uuid(JsonNode body, String name, boolean command) {
        JsonNode node = body.get(name);
        if (node == null || !node.isString()) throw new InvalidRequestException();
        try {
            UUID id = UUID.fromString(node.stringValue());
            if (!id.toString().equals(node.stringValue())) throw new InvalidRequestException();
            return command ? UuidPolicy.requireCommandId(id) : UuidPolicy.requireEntityId(id, name);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
