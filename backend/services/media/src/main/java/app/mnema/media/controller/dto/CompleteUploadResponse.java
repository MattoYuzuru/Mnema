package app.mnema.media.controller.dto;

import app.mnema.media.domain.type.MediaStatus;

import java.util.UUID;

public record CompleteUploadResponse(
        UUID mediaId,
        MediaStatus status
) {
}
