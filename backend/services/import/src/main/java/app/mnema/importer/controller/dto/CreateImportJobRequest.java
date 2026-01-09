package app.mnema.importer.controller.dto;

import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.domain.ImportSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record CreateImportJobRequest(
        @NotNull ImportSourceType sourceType,
        @NotBlank String sourceLocation,
        String sourceName,
        @PositiveOrZero Long sourceSizeBytes,
        UUID targetDeckId,
        ImportMode mode
) {
}
