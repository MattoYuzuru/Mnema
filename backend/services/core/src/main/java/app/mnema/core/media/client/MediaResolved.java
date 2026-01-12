package app.mnema.core.media.client;

import java.time.Instant;
import java.util.UUID;

public record MediaResolved(
        UUID mediaId,
        String kind,
        String url,
        String mimeType,
        Long sizeBytes,
        Integer durationSeconds,
        Integer width,
        Integer height,
        Instant expiresAt
) {
}
