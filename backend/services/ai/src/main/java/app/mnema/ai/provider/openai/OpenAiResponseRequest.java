package app.mnema.ai.provider.openai;

public record OpenAiResponseRequest(
        String model,
        String input,
        Integer maxOutputTokens
) {
}
