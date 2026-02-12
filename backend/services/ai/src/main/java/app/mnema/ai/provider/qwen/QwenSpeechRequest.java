package app.mnema.ai.provider.qwen;

public record QwenSpeechRequest(
        String model,
        String input,
        String voice,
        String languageType
) {
}
