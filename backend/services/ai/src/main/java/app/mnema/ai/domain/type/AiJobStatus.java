package app.mnema.ai.domain.type;

public enum AiJobStatus {
    queued,
    processing,
    partial_success,
    completed,
    failed,
    canceled
}
