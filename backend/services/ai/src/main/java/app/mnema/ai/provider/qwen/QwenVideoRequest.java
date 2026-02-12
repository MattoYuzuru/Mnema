package app.mnema.ai.provider.qwen;

public record QwenVideoRequest(
        String model,
        String prompt,
        Integer durationSeconds,
        String size
) {
}
