package app.mnema.importer.controller.dto;

import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportJobType;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.domain.ImportSourceType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ImportJobResponse(
        UUID jobId,
        ImportJobType jobType,
        ImportJobStatus status,
        ImportSourceType sourceType,
        String sourceName,
        String sourceLocation,
        Long sourceSizeBytes,
        UUID sourceMediaId,
        UUID targetDeckId,
        ImportMode mode,
        Integer totalItems,
        Integer processedItems,
        JsonNode fieldMapping,
        String deckName,
        UUID resultMediaId,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
) {
}
