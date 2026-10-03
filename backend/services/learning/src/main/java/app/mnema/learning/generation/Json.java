package app.mnema.learning.generation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** JSON helpers of the generation module: stored documents are read and written as trees, never as beans. */
final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Json() { }

    static ObjectNode object() { return NODES.objectNode(); }

    static ArrayNode array() { return NODES.arrayNode(); }

    static JsonNode read(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored JSON is unreadable");
        }
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("JSON cannot be written");
        }
    }

    /** UTC RFC 3339, whole seconds are not forced: contract examples are informative, clients parse the instant. */
    static String time(Instant instant) { return instant.toString(); }
}
