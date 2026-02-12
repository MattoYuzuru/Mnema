package app.mnema.ai.provider.qwen;

import com.fasterxml.jackson.databind.JsonNode;

public record QwenChatRequest(
        String model,
        String input,
        Integer maxOutputTokens,
        JsonNode responseFormat
) {
}
