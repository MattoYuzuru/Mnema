package app.mnema.learning.community;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact JSON boundary: unknown fields and wrong node types never become a 500 or personal telemetry. */
final class RecommendationCommands {
    static final Set<String> SURFACES = Set.of("catalog", "search", "for-you", "author", "detail", "copy", "study", "decks");
    static final Set<String> VERSIONS = Set.of("catalog-v1", "recs-v1");
    private static final int MAX_BODY = 16_384;
    private static final ContentJsonReader BODY = new ContentJsonReader(MAX_BODY, 4, 256);

    private RecommendationCommands() {
    }

    record Event(UUID commandId, String kind, String code, String surface, Integer position, String rankVersion, JsonNode envelope) { }

    record Exposure(UUID commandId, String surface, String rankVersion, List<String> codes, JsonNode envelope) { }

    record Selection(UUID commandId, List<String> topics, JsonNode envelope) { }

    static JsonNode read(InputStream input, Set<String> fields) {
        try {
            JsonNode body = BODY.read(input.readNBytes(MAX_BODY + 1));
            if (!body.isObject() || body.size() != fields.size() || !fields.stream().allMatch(body::has)) throw new InvalidRequestException();
            return body;
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw new InvalidRequestException();
        }
    }

    static UUID command(JsonNode body) {
        String text = text(body, "commandId");
        try {
            if (text.length() != 36) throw new InvalidRequestException();
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new InvalidRequestException();
            return UuidPolicy.requireCommandId(id);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    static String text(JsonNode body, String field) {
        if (!body.path(field).isString()) throw new InvalidRequestException();
        return body.path(field).stringValue(null);
    }

    static String code(JsonNode body) {
        String code = text(body, "code");
        if (!code.matches("[1-9A-HJ-NP-Za-km-z]{10}")) throw new InvalidRequestException();
        return code;
    }

    private static String allowed(JsonNode body, String field, Set<String> allowed) {
        String value = text(body, field);
        if (!allowed.contains(value)) throw new InvalidRequestException();
        return value;
    }

    private static Integer position(JsonNode body) {
        JsonNode value = body.path("position");
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 1000) throw new InvalidRequestException();
        return value.intValue();
    }

    static Event event(InputStream input) {
        JsonNode body = read(input, Set.of("commandId", "kind", "code", "surface", "position", "rankVersion"));
        return new Event(command(body), allowed(body, "kind", Set.of("DETAIL_VIEW", "SEARCH_CLICK")), code(body), allowed(body, "surface", SURFACES), position(body), allowed(body,
                "rankVersion", VERSIONS), body);
    }

    static Exposure exposure(InputStream input) {
        JsonNode body = read(input, Set.of("commandId", "surface", "rankVersion", "items"));
        JsonNode items = body.path("items");
        if (!items.isArray() || items.size() < 1 || items.size() > 48) throw new InvalidRequestException();
        List<String> codes = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject() || item.size() != 2 || !item.has("code") || !item.has("position")) throw new InvalidRequestException();
            codes.add(code(item));
            position(item);
        }

        if (new HashSet<>(codes).size() != codes.size()) throw new InvalidRequestException();
        return new Exposure(command(body), allowed(body, "surface", SURFACES), allowed(body, "rankVersion", VERSIONS), List.copyOf(codes), body);
    }

    static Selection selection(InputStream input) {
        JsonNode body = read(input, Set.of("commandId", "topicIds"));
        JsonNode array = body.path("topicIds");
        if (!array.isArray() || array.size() > 20) throw new InvalidRequestException();
        List<String> topics = new ArrayList<>();
        for (JsonNode topic : array) {
            if (!topic.isString()) throw new InvalidRequestException();
            topics.add(topic.stringValue(null));
        }

        if (new HashSet<>(topics).size() != topics.size()) throw new InvalidRequestException();
        return new Selection(command(body), List.copyOf(topics), body);
    }
}
