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
        /** The asset is reserved but its bytes are not sealed yet (a crash between the reservation and the transfer): stage them again. */
        PENDING,
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
     * Reserves the asset {@code assetId} (origin {@code generated}) and its upload session for these bytes without transferring them, so that the
     * caller can record what the asset is <em>before</em> the transfer; a retry after a crash then resumes that choice. Idempotent for the same
     * bytes; other bytes replace a reservation whose transfer has not begun.
     *
     * @throws app.mnema.learning.platform.api.InvalidRequestException the type or length is not allowed for {@code kind}
     */
    void reserve(UUID owner, UUID assetId, MediaCatalog.Kind kind, String mimeType, byte[] bytes);

    /**
     * Creates the asset {@code assetId} (origin {@code generated}) of {@code owner} and starts its verification. Idempotent for the same bytes (a reservation of other bytes whose transfer has not begun is replaced; a transferred one conflicts).
     *
     * @throws app.mnema.learning.platform.api.InvalidRequestException the type or length is not allowed for {@code kind}
     * @throws MediaStorageUnavailableException the object store cannot be reached
     */
    void stage(UUID owner, UUID assetId, MediaCatalog.Kind kind, String mimeType, byte[] bytes);

    State assetState(UUID owner, UUID assetId);
}
