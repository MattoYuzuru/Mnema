package app.mnema.ai.provider.openai;

public record OpenAiSpeechRequest(
        String model,
        String input,
        String voice,
        String responseFormat
) {
}
