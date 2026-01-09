package app.mnema.importer.controller.dto;

import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.domain.ImportSourceType;

import java.time.Instant;
import java.util.UUID;

public record ImportJobResponse(
        UUID jobId,
        ImportJobStatus status,
        ImportSourceType sourceType,
        String sourceName,
        String sourceLocation,
        Long sourceSizeBytes,
        UUID targetDeckId,
        ImportMode mode,
        Integer totalItems,
        Integer processedItems,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
) {
}
