package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure operations of an edit on a native-v1 document: the top-level blocks, the media nodes, the handles of a revision and the
 * replacement of a block range. A block the edit does not touch is carried over as the very same JSON (same node IDs, same bytes
 * once serialized), so an edit can never change what it was not asked to.
 */
final class EditDocument {
    private static final Set<String> MEDIA = Set.of("audio", "image", "video");

    private EditDocument() { }

    /** The top-level blocks of the document, in order. */
    static List<JsonNode> blocks(JsonNode document) {
        List<JsonNode> blocks = new ArrayList<>();
        document.path("root").path("content").forEach(blocks::add);
        return blocks;
    }

    static boolean isMedia(JsonNode block) {
        return MEDIA.contains(block.path("type").stringValue(""));
    }

    static UUID id(JsonNode block) {
        return UUID.fromString(block.path("id").stringValue(""));
    }

    /** Node id of each top-level block, in order. */
    static List<UUID> ids(JsonNode document) {
        return blocks(document).stream().map(EditDocument::id).toList();
    }

    /** {@code b1 -> node id, b2 -> ...} of the top-level blocks: the handles an edit of this revision shows the model. */
    static Map<String, UUID> handles(JsonNode document) {
        Map<String, UUID> handles = new LinkedHashMap<>();
        int number = 1;
        for (JsonNode block : blocks(document)) handles.put("b" + number++, id(block));
        return handles;
    }

    /**
     * The document with the blocks {@code from..to} (inclusive indexes of the top-level blocks) replaced by {@code replacement}. The
     * media blocks of {@code run} (the original blocks of that range) are never shown to the model and an edit never drops them: each
     * stays right after the rewritten block that carries the node id of the nearest text block before it in the run (that id survives a
     * rewrite whose block keeps its type), in its original order; one that opened the run stays first; one whose anchor is gone or
     * changed type goes to the end of the range. Everything outside the range is the original JSON.
     */
    static JsonNode replace(JsonNode document, int from, int to, List<JsonNode> replacement, List<JsonNode> run) {
        Set<UUID> rewritten = new LinkedHashSet<>();
        replacement.forEach(block -> rewritten.add(id(block)));
        List<JsonNode> leading = new ArrayList<>();
        List<JsonNode> tail = new ArrayList<>();
        Map<UUID, List<JsonNode>> after = new LinkedHashMap<>();
        UUID anchor = null;
        for (JsonNode block : run) {
            if (!isMedia(block)) {
                anchor = id(block);
            } else if (anchor == null) {
                leading.add(block);
            } else if (rewritten.contains(anchor)) {
                after.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(block);
            } else {
                tail.add(block);
            }
        }
        JsonNode copy = document.deepCopy();
        ObjectNode root = (ObjectNode) copy.path("root");
        ArrayNode content = (ArrayNode) root.path("content");
        List<JsonNode> merged = new ArrayList<>();
        for (int index = 0; index < from; index++) merged.add(content.get(index));
        leading.forEach(block -> merged.add(block.deepCopy()));
        for (JsonNode block : replacement) {
            merged.add(block.deepCopy());
            after.getOrDefault(id(block), List.of()).forEach(media -> merged.add(media.deepCopy()));
        }
        tail.forEach(block -> merged.add(block.deepCopy()));
        for (int index = to + 1; index < content.size(); index++) merged.add(content.get(index));
        ArrayNode rebuilt = root.putArray("content");
        merged.forEach(rebuilt::add);
        return copy;
    }

    /** The document without the top-level blocks {@code ids}. */
    static JsonNode without(JsonNode document, Set<UUID> ids) {
        JsonNode copy = document.deepCopy();
        ObjectNode root = (ObjectNode) copy.path("root");
        List<JsonNode> kept = new ArrayList<>();
        root.path("content").forEach(block -> {
            if (!ids.contains(id(block))) kept.add(block);
        });
        ArrayNode rebuilt = root.putArray("content");
        kept.forEach(rebuilt::add);
        return copy;
    }

    /** Node ids of the document's top-level blocks as a set, for membership tests. */
    static Set<UUID> idSet(JsonNode document) {
        return new LinkedHashSet<>(ids(document));
    }
}
