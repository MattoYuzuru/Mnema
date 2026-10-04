package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/** Operations on the media nodes of a native-v1 document that image search needs: which asset a node uses, and the same document using another. */
final class MediaNodes {
    private MediaNodes() { }

    /** The top-level block {@code nodeId}, or null. */
    static JsonNode block(JsonNode document, UUID nodeId) {
        for (JsonNode block : EditDocument.blocks(document)) if (EditDocument.id(block).equals(nodeId)) return block;
        return null;
    }

    static boolean has(JsonNode document, UUID nodeId) {
        return block(document, nodeId) != null;
    }

    /** The {@code assetId} the media node uses, or null when the node is not there or has none. */
    static UUID assetOf(JsonNode document, UUID nodeId) {
        JsonNode block = block(document, nodeId);
        String asset = block == null ? null : block.path("attrs").path("assetId").stringValue(null);
        try {
            return asset == null ? null : UUID.fromString(asset);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** A copy of the document whose node {@code nodeId} uses {@code assetId}; every other byte is the original. */
    static JsonNode withAsset(JsonNode document, UUID nodeId, UUID assetId) {
        JsonNode copy = document.deepCopy();
        JsonNode block = block(copy, nodeId);
        if (block == null) throw new IllegalArgumentException("No such node");
        ((ObjectNode) block.path("attrs")).put("assetId", assetId.toString());
        return copy;
    }
}
