package app.mnema.media.controller.dto;

import app.mnema.media.domain.type.MediaKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateUploadRequest(
        @NotNull MediaKind kind,
        @NotBlank String contentType,
        @Positive long sizeBytes,
        String fileName
) {
}
