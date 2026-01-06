package app.mnema.media.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateUploadResponse(
        UUID mediaId,
        UUID uploadId,
        boolean multipart,
        String url,
        Map<String, String> headers,
        List<UploadPartResponse> parts,
        Integer partsCount,
        Long partSizeBytes,
        Instant expiresAt
) {
}
