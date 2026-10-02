package app.mnema.learning.catalog.content;

import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stable node IDs of one pinned item revision and their plain-text projections. A MATERIAL block quotes
 * a node through this index both when an exercise is published and when it is issued, so both sides see
 * the same text. Unknown or future node types project to nothing: they stay inert data.
 */
public final class NativeNodeIndex {
    private final Map<UUID, JsonNode> nodes;

    private NativeNodeIndex(Map<UUID, JsonNode> nodes) { this.nodes = nodes; }

    /** Reads the complete immutable snapshot rooted at {@code contentRoot}; the caller authorized the root. */
    public static NativeNodeIndex load(NativeStorageBatches batches, ObjectRef contentRoot) {
        NativeSnapshotDecoder decoder = new NativeSnapshotDecoder(contentRoot);
        while (!decoder.isComplete()) batches.readNext(decoder);
        Map<UUID, JsonNode> nodes = new HashMap<>();
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(decoder.snapshot().document().toJson().path("root"));
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            nodes.put(UUID.fromString(node.path("id").stringValue(null)), node);
            node.path("content").forEach(pending::add);
        }
        return new NativeNodeIndex(nodes);
    }

    /** Plain text of the node, or empty when the node does not exist. The text may be empty for media. */
    public Optional<String> text(UUID nodeId) { return Optional.ofNullable(nodes.get(nodeId)).map(NativeNodeIndex::project); }

    private static String project(JsonNode node) {
        if (!NativeNodeSchema.supports(node.path("type").stringValue(null), node.path("version").intValue(0))) return "";
        JsonNode attrs = node.path("attrs");
        return switch (node.path("type").stringValue(null)) {
            case "text" -> attrs.path("text").asString("");
            case "ruby" -> attrs.path("base").asString("");
            case "paragraph", "heading", "link" -> {
                StringBuilder inline = new StringBuilder();
                node.path("content").forEach(child -> inline.append(project(child)));
                yield inline.toString();
            }
            case "doc", "blockquote", "bullet_list", "ordered_list", "list_item" -> {
                StringBuilder blocks = new StringBuilder();
                for (JsonNode child : node.path("content")) {
                    String text = project(child);
                    if (text.isEmpty()) continue;
                    if (!blocks.isEmpty()) blocks.append('\n');
                    blocks.append(text);
                }
                yield blocks.toString();
            }
            default -> "";
        };
    }
}
