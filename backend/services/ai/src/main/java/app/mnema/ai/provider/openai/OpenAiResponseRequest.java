package app.mnema.ai.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;

public record OpenAiResponseRequest(
        String model,
        String input,
        Integer maxOutputTokens,
        JsonNode responseFormat
) {
}
