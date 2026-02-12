package app.mnema.ai.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;

public record OpenAiResponseResult(
        String outputText,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        JsonNode raw
) {
}
