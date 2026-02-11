package app.mnema.ai.provider.qwen;

public record QwenVideoJob(
        String taskId,
        String status,
        String model,
        String videoUrl,
        String error
) {
    public boolean isCompleted() {
        return "SUCCEEDED".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "FAILED".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status);
    }
}
