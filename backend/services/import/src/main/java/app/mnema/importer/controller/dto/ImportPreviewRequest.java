package app.mnema.importer.controller.dto;

import app.mnema.importer.domain.ImportSourceType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ImportPreviewRequest(
        @NotNull UUID sourceMediaId,
        @NotNull ImportSourceType sourceType,
        UUID targetDeckId,
        Integer sampleSize
) {
}
