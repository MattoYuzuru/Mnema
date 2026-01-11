package app.mnema.importer.client.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
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
