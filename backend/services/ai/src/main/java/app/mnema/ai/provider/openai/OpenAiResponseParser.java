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
                    if ("output_text".equals(type) || "text".equals(type)) {
                        appendText(builder, part.path("text").asText());
                    }
                }
            }
            return builder.toString();
        }
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode first = choices.get(0);
            JsonNode message = first.path("message");
            JsonNode content = message.get("content");
            if (content != null) {
                if (content.isTextual()) {
                    return content.asText();
                }
                if (content.isArray()) {
                    StringBuilder builder = new StringBuilder();
                    for (JsonNode part : content) {
                        appendText(builder, part.path("text").asText(""));
                    }
                    return builder.toString();
                }
            }
        }
        return "";
    }

    private static void appendText(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text);
    }
}
