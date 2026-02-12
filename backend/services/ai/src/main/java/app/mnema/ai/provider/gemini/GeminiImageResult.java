package app.mnema.ai.provider.gemini;

public record GeminiImageResult(
        byte[] data,
        String mimeType,
        String model
) {
}
