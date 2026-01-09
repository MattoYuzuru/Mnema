package app.mnema.importer.controller.dto;

import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.domain.ImportSourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;
import java.util.UUID;

public record CreateImportJobRequest(
        @NotNull UUID sourceMediaId,
        @NotNull ImportSourceType sourceType,
        String sourceName,
        @PositiveOrZero Long sourceSizeBytes,
        UUID targetDeckId,
        ImportMode mode,
        String deckName,
        Map<String, String> fieldMapping
) {
}
