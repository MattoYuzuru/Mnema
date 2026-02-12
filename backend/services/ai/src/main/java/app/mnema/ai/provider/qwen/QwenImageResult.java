package app.mnema.ai.provider.qwen;

public record QwenImageResult(
        byte[] data,
        String mimeType,
        String model
) {
}
