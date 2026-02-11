package app.mnema.ai.provider.openai;

public record OpenAiTranscriptionRequest(
        String model,
        String language,
        String responseFormat,
        String mimeType,
        String fileName,
        byte[] audio
) {
}
