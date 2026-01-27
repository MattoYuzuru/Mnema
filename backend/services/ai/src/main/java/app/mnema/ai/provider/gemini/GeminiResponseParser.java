package app.mnema.ai.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Base64;

public final class GeminiResponseParser {

    private GeminiResponseParser() {
    }

    public static String extractText(JsonNode response) {
        if (response == null) {
            return "";
        }
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return "";
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText(null);
            if (text != null && !text.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    public static AudioResult extractAudio(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode inline = response.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("inlineData");
        String data = inline.path("data").asText(null);
        if (data == null || data.isBlank()) {
            return null;
        }
        String mimeType = inline.path("mimeType").asText(null);
        byte[] decoded = Base64.getDecoder().decode(data);
        return new AudioResult(decoded, mimeType);
    }

    public record AudioResult(byte[] data, String mimeType) {
    }
}
