package app.mnema.ai.provider.grok;

import com.fasterxml.jackson.databind.JsonNode;

public record GrokResponseRequest(
        String model,
        String input,
        Integer maxOutputTokens,
        JsonNode responseFormat
) {
}
