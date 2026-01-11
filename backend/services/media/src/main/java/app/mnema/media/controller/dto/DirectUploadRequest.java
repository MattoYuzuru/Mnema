package app.mnema.media.controller.dto;

import app.mnema.media.domain.type.MediaKind;

import java.util.UUID;

public record DirectUploadRequest(
        MediaKind kind,
        String contentType,
        String fileName,
        UUID ownerUserId
) {
}
