package app.mnema.learning.catalog.content;

import com.fasterxml.jackson.databind.JsonNode;

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
        if (!NativeNodeSchema.supports(node.path("type").asText(), node.path("version").asInt())) return "";
        if (TEXT_BLOCKS.contains(node.path("type").asText())) {
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
        if (!NativeNodeSchema.supports(node.path("type").asText(), node.path("version").asInt())) return;
        if ("text".equals(node.path("type").asText())) text.append(node.path("attrs").path("text").asText());
        if ("ruby".equals(node.path("type").asText())) text.append(node.path("attrs").path("base").asText());
        for (JsonNode child : node.path("content")) appendText(child, text);
    }
}
