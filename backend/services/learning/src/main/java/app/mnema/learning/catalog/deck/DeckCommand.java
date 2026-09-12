package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Strict, bounded HTTP command boundary shared by create and metadata replacement. */
public record DeckCommand(UUID commandId, String title, String description) {
    public static final int MAX_REQUEST_BYTES = 8192;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_REQUEST_BYTES, 4, 32);

    public DeckCommand {
        try {
            UuidPolicy.requireCommandId(commandId);
            if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 200
                    || title.getBytes(StandardCharsets.UTF_8).length > 800 || description == null
                    || description.getBytes(StandardCharsets.UTF_8).length > 4096) throw new InvalidRequestException();
            requireUnicode(title);
            requireUnicode(description);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidRequestException();
        }
    }

    public static DeckCommand read(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            requireFields(body, Set.of("commandId", "metadata"));
            JsonNode metadata = body.path("metadata");
            requireFields(metadata, Set.of("title", "description"));
            return new DeckCommand(entityId(text(body, "commandId")), text(metadata, "title"), text(metadata, "description"));
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    static UUID entityId(String value) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "id");
            if (!id.toString().equalsIgnoreCase(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    ObjectNode metadata() {
        return JsonNodeFactory.instance.objectNode().put("title", title).put("description", description);
    }

    /** Path and precondition belong to command identity, not just the supplied JSON body. */
    ObjectNode envelope(UUID deckId, Long expectedVersion) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("metadata", metadata());
        if (deckId != null) result.put("deckId", deckId.toString());
        if (expectedVersion != null) result.put("expectedVersion", expectedVersion.toString());
        return result;
    }

    private static String text(JsonNode node, String name) {
        if (!node.path(name).isTextual()) throw new InvalidRequestException();
        return node.path(name).textValue();
    }

    private static void requireFields(JsonNode node, Set<String> fields) {
        if (!node.isObject() || node.size() != fields.size()
                || node.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw new InvalidRequestException();
        }
    }

    private static void requireUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == 0 || Character.isLowSurrogate(current)) throw new InvalidRequestException();
            if (Character.isHighSurrogate(current)
                    && (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))) throw new InvalidRequestException();
        }
    }
}
