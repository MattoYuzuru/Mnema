package app.mnema.ai.provider.grok;

public record GrokVideoJob(
        String requestId,
        String status,
        String model,
        String videoUrl,
        String error
) {
    public boolean isCompleted() {
        return "done".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status);
    }
}
