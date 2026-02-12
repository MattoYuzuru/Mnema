package app.mnema.ai.provider.grok;

public record GrokImageResult(
        byte[] data,
        String mimeType,
        String revisedPrompt,
        String model
) {
}
