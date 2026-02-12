package app.mnema.ai.provider.claude;

import com.fasterxml.jackson.databind.JsonNode;

public record ClaudeResponseResult(
        String outputText,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        JsonNode raw
) {
}
