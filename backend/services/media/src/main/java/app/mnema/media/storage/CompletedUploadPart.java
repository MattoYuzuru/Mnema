package app.mnema.media.storage;

public record CompletedUploadPart(
        int partNumber,
        String eTag
) {
}
