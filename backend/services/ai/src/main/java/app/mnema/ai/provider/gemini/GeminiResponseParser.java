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
        InlineDataResult inline = extractInlineData(response);
        if (inline == null) {
            return null;
        }
        return new AudioResult(inline.data(), inline.mimeType());
    }

    public record AudioResult(byte[] data, String mimeType) {
    }

    public static InlineDataResult extractInlineData(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray()) {
            return null;
        }
        for (JsonNode candidate : candidates) {
            JsonNode parts = candidate.path("content").path("parts");
            if (!parts.isArray()) {
                continue;
            }
            for (JsonNode part : parts) {
                JsonNode inline = part.path("inlineData");
                if (inline.isMissingNode() || inline.isNull()) {
                    inline = part.path("inline_data");
                }
                String data = inline.path("data").asText(null);
                if (data == null || data.isBlank()) {
                    continue;
                }
                String mimeType = inline.path("mimeType").asText(null);
                if (mimeType == null || mimeType.isBlank()) {
                    mimeType = inline.path("mime_type").asText(null);
                }
                byte[] decoded = Base64.getDecoder().decode(data);
                return new InlineDataResult(decoded, mimeType);
            }
        }
        return null;
    }

    public record InlineDataResult(byte[] data, String mimeType) {
    }
}
