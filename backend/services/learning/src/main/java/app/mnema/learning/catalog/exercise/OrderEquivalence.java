package app.mnema.learning.catalog.exercise;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The narrow ORDER equivalence: two items are interchangeable only when their blocks are identical as the
 * learner sees them (the same canonical JSON). Nothing else, not even semantically similar text, is equivalent.
 *
 * <p>The signature ignores author-only labels ({@code title} of audio/video and the derived
 * {@code transcriptAvailable} flag) so authored content (author preview) and issued learner content (Study)
 * agree; item identifiers are never part of it. Study and preview use this one definition for issuing the
 * shuffle, per-position correctness and the result.
 */
public final class OrderEquivalence {
    private OrderEquivalence() { }

    /** Item id to equivalence signature for an {@code items: [{itemId, blocks}]} array. */
    public static Map<UUID, String> signatures(JsonNode items) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (JsonNode item : items) result.put(UUID.fromString(item.path("itemId").stringValue(null)), signature(item.path("blocks")));
        return result;
    }

    /** Equivalence classes of a sequence of item ids, position by position. */
    public static List<String> classes(List<UUID> sequence, Map<UUID, String> signatures) {
        return sequence.stream().map(signatures::get).toList();
    }

    static String signature(JsonNode blocks) {
        ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
        for (JsonNode block : blocks) canonical.add(canonical(block));
        return canonical.toString();
    }

    /** Object fields in lexicographic order, minus author-only data. */
    private static JsonNode canonical(JsonNode block) {
        if (!block.isObject()) return block;
        String kind = block.path("kind").asString("");
        boolean timed = kind.equals("AUDIO") || kind.equals("VIDEO");
        Map<String, JsonNode> sorted = new TreeMap<>();
        block.properties().forEach(field -> {
            if (timed && (field.getKey().equals("title") || field.getKey().equals("transcriptAvailable"))) return;
            sorted.put(field.getKey(), field.getValue());
        });
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        sorted.forEach(result::set);
        return result;
    }
}
