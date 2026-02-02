package app.mnema.ai.provider.openai;

public record OpenAiImageResult(
        byte[] data,
        String mimeType,
        String revisedPrompt,
        String model
) {
}
