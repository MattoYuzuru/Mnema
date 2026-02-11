package app.mnema.ai.provider.qwen;

public record QwenSpeechResult(
        byte[] data,
        String mimeType
) {
}
