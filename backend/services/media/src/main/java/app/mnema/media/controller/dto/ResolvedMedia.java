package app.mnema.media.controller.dto;

import app.mnema.media.domain.type.MediaKind;

import java.time.Instant;
import java.util.UUID;

public record ResolvedMedia(
        UUID mediaId,
        MediaKind kind,
        String url,
        String mimeType,
        Long sizeBytes,
        Integer durationSeconds,
        Integer width,
        Integer height,
        Instant expiresAt
) {
}
