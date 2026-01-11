package app.mnema.importer.client.media;

import java.util.UUID;

public record MediaDirectUploadRequest(
        String kind,
        String contentType,
        String fileName,
        UUID ownerUserId
) {
}
