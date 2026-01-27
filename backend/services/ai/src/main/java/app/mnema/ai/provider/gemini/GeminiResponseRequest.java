package app.mnema.ai.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;

public record GeminiResponseRequest(
        String model,
        String input,
        Integer maxOutputTokens,
        String responseMimeType,
        JsonNode responseSchema
) {
}
