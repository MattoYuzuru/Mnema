package app.mnema.ai.provider.grok;

public record GrokTranscriptionRequest(
        String model,
        String language,
        String responseFormat,
        String mimeType,
        String fileName,
        byte[] audio
) {
}
