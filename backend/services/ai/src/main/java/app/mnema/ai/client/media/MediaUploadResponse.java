package app.mnema.ai.client.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaUploadResponse(
        UUID mediaId,
        String status
) {
}
