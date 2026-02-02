package app.mnema.ai.provider.openai;

public record OpenAiVideoRequest(
        String model,
        String prompt,
        Integer durationSeconds,
        String resolution
) {
}
