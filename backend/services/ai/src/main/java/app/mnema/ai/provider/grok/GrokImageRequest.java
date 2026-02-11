package app.mnema.ai.provider.grok;

public record GrokImageRequest(
        String model,
        String prompt,
        String aspectRatio,
        String resolution,
        String responseFormat
) {
}
