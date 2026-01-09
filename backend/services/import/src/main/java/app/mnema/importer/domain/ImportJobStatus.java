package app.mnema.importer.domain;

public enum ImportJobStatus {
    queued,
    processing,
    completed,
    failed,
    canceled
}
