package app.mnema.ai.provider.openai;

public record OpenAiImageRequest(
        String model,
        String prompt,
        String size,
        String quality,
        String style,
        String format
) {
}
