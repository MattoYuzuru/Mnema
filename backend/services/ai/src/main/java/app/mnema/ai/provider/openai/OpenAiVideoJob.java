package app.mnema.ai.provider.openai;

public record OpenAiVideoJob(
        String id,
        String status,
        Integer progress,
        String model,
        String error
) {
    public boolean isCompleted() {
        return "completed".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "failed".equalsIgnoreCase(status);
    }
}
