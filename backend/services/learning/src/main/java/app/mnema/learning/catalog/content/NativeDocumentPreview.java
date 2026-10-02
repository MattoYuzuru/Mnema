package app.mnema.learning.catalog.content;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/** First readable block in document order; opaque and media payloads are never interpreted as text. */
public final class NativeDocumentPreview {
    public static final int MAX_CODE_POINTS = 240;
    private static final Set<String> TEXT_BLOCKS = Set.of("heading", "paragraph");
    private NativeDocumentPreview() { }

    public static String title(NativeDocument document) {
        return first(document.toJson().path("root"));
    }

    private static String first(JsonNode node) {
        if (!NativeNodeSchema.supports(node.path("type").asString(""), node.path("version").asInt(0))) return "";
        if (TEXT_BLOCKS.contains(node.path("type").asString(""))) {
            StringBuilder text = new StringBuilder();
            appendText(node, text);
            String normalized = text.toString().strip().replaceAll("\\s+", " ");
            if (!normalized.isEmpty()) {
                int length = normalized.codePointCount(0, normalized.length());
                return normalized.substring(0, normalized.offsetByCodePoints(0, Math.min(length, MAX_CODE_POINTS)));
            }
        }
        for (JsonNode child : node.path("content")) {
            String text = first(child);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static void appendText(JsonNode node, StringBuilder text) {
        if (!NativeNodeSchema.supports(node.path("type").asString(""), node.path("version").asInt(0))) return;
        if ("text".equals(node.path("type").asString(""))) text.append(node.path("attrs").path("text").asString(""));
        if ("ruby".equals(node.path("type").asString(""))) text.append(node.path("attrs").path("base").asString(""));
        for (JsonNode child : node.path("content")) appendText(child, text);
    }
}
