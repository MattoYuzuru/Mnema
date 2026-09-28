package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** Small, strict JSON boundary: transfer commands never carry media bytes through Learning. */
final class MediaUploadCommand {
    private static final int MAX_BYTES = 2048;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_BYTES, 3, 32);

    private MediaUploadCommand() { }

    static Start start(InputStream input) {
        JsonNode body = read(input, Set.of("intentId", "origin", "kind", "mime", "byteLength"));
        return new Start(id(body, "intentId"), text(body, "origin"), text(body, "kind"),
                text(body, "mime"), number(body, "byteLength"));
    }

    static Retry retry(InputStream input) {
        JsonNode body = read(input, Set.of("commandId", "kind", "mime", "byteLength"));
        return new Retry(id(body, "commandId"), text(body, "kind"), text(body, "mime"),
                number(body, "byteLength"));
    }

    static PartRange partRange(InputStream input) {
        JsonNode body = read(input, Set.of("generation", "firstPart", "count"));
        long first = number(body, "firstPart");
        long count = number(body, "count");
        if (first < 1 || first > Integer.MAX_VALUE || count < 1 || count > Integer.MAX_VALUE) {
            throw new InvalidRequestException();
        }
        return new PartRange(number(body, "generation"), (int) first, (int) count);
    }

    static long generation(InputStream input) {
        return number(read(input, Set.of("generation")), "generation");
    }

    static Finalize finalizeCommand(InputStream input) {
        JsonNode body = read(input, Set.of("commandId", "generation"));
        return new Finalize(id(body, "commandId"), number(body, "generation"));
    }

    private static JsonNode read(InputStream input, Set<String> fields) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_BYTES + 1));
            if (body.size() != fields.size() || body.properties().stream()
                    .anyMatch(entry -> !fields.contains(entry.getKey()))) throw new InvalidRequestException();
            return body;
        } catch (IOException | IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }

    private static UUID id(JsonNode body, String field) {
        String value = text(body, field);
        try {
            if (value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireCommandId(UUID.fromString(value));
            if (!id.toString().equalsIgnoreCase(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }

    private static String text(JsonNode body, String field) {
        if (!body.path(field).isTextual()) throw new InvalidRequestException();
        return body.path(field).textValue();
    }

    private static long number(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new InvalidRequestException();
        }
        return value.longValue();
    }

    record Start(UUID intentId, String origin, String kind, String mime, long byteLength) { }
    record Retry(UUID commandId, String kind, String mime, long byteLength) { }
    record PartRange(long generation, int firstPart, int count) { }
    record Finalize(UUID commandId, long generation) { }
}
