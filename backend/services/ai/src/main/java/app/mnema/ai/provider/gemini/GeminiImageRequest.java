package app.mnema.ai.provider.gemini;

public record GeminiImageRequest(
        String model,
        String prompt
) {
}
