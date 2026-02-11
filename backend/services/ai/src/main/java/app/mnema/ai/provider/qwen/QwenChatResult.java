package app.mnema.ai.provider.qwen;

import com.fasterxml.jackson.databind.JsonNode;

public record QwenChatResult(
        String outputText,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        JsonNode rawResponse
) {
}
