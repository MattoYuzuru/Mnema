package app.mnema.importer.controller.dto;

import app.mnema.importer.domain.ImportSourceType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateExportJobRequest(
        @NotNull UUID userDeckId,
        @NotNull ImportSourceType format
) {
}
