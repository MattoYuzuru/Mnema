package app.mnema.ai.provider.claude;

public record ClaudeMessageRequest(
        String model,
        String input,
        Integer maxOutputTokens
) {
}
