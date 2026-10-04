package app.mnema.learning.media;

import java.util.UUID;

/**
 * The server-side way into the media pipeline for bytes the server itself obtained (a downloaded stock image, later generated media): the bytes
 * are staged as an <em>untrusted upload</em> under an asset identity the caller chose, and go through the same finalize, seal, verification and
 * derivation as a browser upload, ending {@code READY} or {@code REJECTED}. Never inside a database transaction (it does S3 I/O).
 */
public interface GeneratedMediaStager {
    /** Where an asset stands for the staging caller. */
    enum State {
        /** Staged and waiting for, or in, verification and processing. */
        VERIFYING,
        READY,
        /** The verification rejected the bytes (not an image of an allowed type, undecodable, too large to process). */
        REJECTED,
        /** Processing failed for good (retries used up) or the asset was deleted. */
        FAILED,
        /** No such asset of this owner. */
        MISSING
    }

    /**
     * Creates the asset {@code assetId} (origin {@code generated}) of {@code owner} and starts its verification. Idempotent for the same bytes.
     *
     * @throws app.mnema.learning.platform.api.InvalidRequestException the type or length is not allowed for {@code kind}
     * @throws MediaStorageUnavailableException the object store cannot be reached
     */
    void stage(UUID owner, UUID assetId, MediaCatalog.Kind kind, String mimeType, byte[] bytes);

    State assetState(UUID owner, UUID assetId);
}
