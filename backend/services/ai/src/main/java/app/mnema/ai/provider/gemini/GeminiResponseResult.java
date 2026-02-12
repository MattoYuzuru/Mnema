package app.mnema.ai.provider.gemini;

import com.fasterxml.jackson.databind.JsonNode;

public record GeminiResponseResult(
        String outputText,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        JsonNode raw
) {
}
