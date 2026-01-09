package app.mnema.importer.controller.dto;

import app.mnema.importer.domain.ImportSourceType;

import java.util.UUID;

public record UploadImportSourceResponse(
        UUID mediaId,
        String fileName,
        long sizeBytes,
        ImportSourceType sourceType
) {
}
