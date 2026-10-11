package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Strict, bounded body of {@code PUT /decks/{deckId}/publication}: the whole desired state ({@code visibility}, {@code metadata}, {@code requestsEnabled})
 * and, when the owner publishes, {@code publish} with the head he saw and the optional «Что нового».
 */
public record PublicationCommand(UUID commandId, DeckVisibility visibility, PublicationMetadata metadata, boolean requestsEnabled, Publish publish) {
    public static final int MAX_REQUEST_BYTES = 8192;
    public static final int MAX_RELEASE_NOTE_CODE_POINTS = 500;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_REQUEST_BYTES, 6, 64);

    /** The owner publishes the current head: {@code expectedHeadRevisionId} pins what he saw, {@code releaseNote} is nullable (blank is null). */
    public record Publish(UUID expectedHeadRevisionId, String releaseNote) { }

    static PublicationCommand read(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            fields(body, Set.of("commandId", "visibility", "metadata", "requestsEnabled", "publish"));
            UUID commandId = UuidPolicy.requireCommandId(uuid(body.path("commandId")));
            if (!body.path("visibility").isString() || !body.path("requestsEnabled").isBoolean()) throw new InvalidRequestException();
            DeckVisibility visibility = DeckVisibility.valueOf(body.path("visibility").stringValue(null));
            PublicationMetadata metadata = PublicationMetadata.read(body.path("metadata"));
            Publish publish = publish(body.path("publish"));
            // Publishing a revision only makes sense at a level that readers can reach.
            if (visibility == DeckVisibility.PRIVATE && publish != null) throw new InvalidRequestException();
            return new PublicationCommand(commandId, visibility, metadata, body.path("requestsEnabled").booleanValue(), publish);
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static Publish publish(JsonNode node) {
        if (node.isNull()) return null;
        fields(node, Set.of("expectedHeadRevisionId", "releaseNote"));
        JsonNode note = node.path("releaseNote");
        String text = null;
        if (!note.isNull()) {
            if (!note.isString()) throw new InvalidRequestException();
            text = note.stringValue(null);
            requireUnicode(text);
            if (text.codePointCount(0, text.length()) > MAX_RELEASE_NOTE_CODE_POINTS) throw new InvalidRequestException();
            if (text.isBlank()) text = null;
        }
        return new Publish(uuid(node.path("expectedHeadRevisionId")), text);
    }

    private static UUID uuid(JsonNode value) {
        if (!value.isString()) throw new InvalidRequestException();
        String text = value.stringValue(null);
        if (text.length() != 36) throw new InvalidRequestException();
        UUID id = UuidPolicy.requireEntityId(UUID.fromString(text), "id");
        if (!id.toString().equalsIgnoreCase(text)) throw new InvalidRequestException();
        return id;
    }

    /** All fields present, none unknown: a typo is never a silently different state. */
    private static void fields(JsonNode node, Set<String> fields) {
        if (!node.isObject() || node.size() != fields.size() || node.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
            throw new InvalidRequestException();
        }
    }

    private static void requireUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == 0 || Character.isLowSurrogate(current)) throw new InvalidRequestException();
            if (Character.isHighSurrogate(current) && (++index == value.length() || !Character.isLowSurrogate(value.charAt(index)))) {
                throw new InvalidRequestException();
            }
        }
    }

    /** The receipt envelope: actor and command type are in the identity; the path, the precondition and the whole state are bound here. */
    ObjectNode envelope(UUID deckId, long expectedVersion) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("commandId", commandId.toString()).put("deckId", deckId.toString()).put("expectedVersion", Long.toString(expectedVersion));
        result.put("visibility", visibility.name());
        result.set("metadata", metadata.toJson());
        result.put("requestsEnabled", requestsEnabled);
        if (publish == null) result.putNull("publish");
        else result.putObject("publish").put("expectedHeadRevisionId", publish.expectedHeadRevisionId().toString()).put("releaseNote", publish.releaseNote());
        return result;
    }
}
