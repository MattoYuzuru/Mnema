package app.mnema.ai.provider.grok;

import com.fasterxml.jackson.databind.JsonNode;

public record GrokResponseResult(
        String outputText,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        JsonNode rawResponse
) {
}
