package app.mnema.ai.provider.claude;

import com.fasterxml.jackson.databind.JsonNode;

public final class ClaudeResponseParser {

    private ClaudeResponseParser() {
    }

    public static String extractText(JsonNode response) {
        if (response == null) {
            return "";
        }
        JsonNode content = response.path("content");
        if (!content.isArray() || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText())) {
                continue;
            }
            String text = block.path("text").asText(null);
            if (text != null && !text.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }
}
