package app.mnema.ai.provider.qwen;

public record QwenImageRequest(
        String model,
        String prompt,
        String size
) {
}
