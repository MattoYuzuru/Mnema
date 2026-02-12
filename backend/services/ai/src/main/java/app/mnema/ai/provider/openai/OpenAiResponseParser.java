package app.mnema.ai.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;

public final class OpenAiResponseParser {

    private OpenAiResponseParser() {
    }

    public static String extractText(JsonNode response) {
        if (response == null) {
            return "";
        }
        JsonNode outputText = response.get("output_text");
        if (outputText != null && outputText.isTextual()) {
            return outputText.asText();
        }
        JsonNode output = response.get("output");
        if (output != null && output.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) {
                    continue;
                }
                for (JsonNode part : content) {
                    String type = part.path("type").asText();
                    if ("output_text".equals(type)) {
                        String text = part.path("text").asText();
                        if (!text.isBlank()) {
                            if (!builder.isEmpty()) {
                                builder.append('\n');
                            }
                            builder.append(text);
                        }
                    }
                }
            }
            return builder.toString();
        }
        return "";
    }
}
