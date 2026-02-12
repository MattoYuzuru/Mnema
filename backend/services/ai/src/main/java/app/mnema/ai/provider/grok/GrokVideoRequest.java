package app.mnema.ai.provider.grok;

public record GrokVideoRequest(
        String model,
        String prompt,
        Integer durationSeconds,
        String size
) {
}
