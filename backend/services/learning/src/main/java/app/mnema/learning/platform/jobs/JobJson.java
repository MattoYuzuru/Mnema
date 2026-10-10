package app.mnema.learning.platform.jobs;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/** The one place that turns a payload or a progress cursor into the text of a {@code jsonb} column, and back, with the bound of the contract. */
final class JobJson {
    /** The most bytes of the compact JSON of a payload or a progress cursor. */
    static final int MAX_BYTES = 16 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JobJson() {
    }

    static String write(ObjectNode node, String what) {
        if (node == null) throw new IllegalArgumentException(what + " must be a JSON object");
        String text = JSON.writeValueAsString(node);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(what + " is larger than " + MAX_BYTES + " bytes");
        }
        return text;
    }

    static ObjectNode read(String text) {
        if (text == null) return null;
        try {
            JsonNode node = JSON.readTree(text);
            return node instanceof ObjectNode object ? object : null;
        } catch (JacksonException corrupt) {
            return null;
        }
    }
}
