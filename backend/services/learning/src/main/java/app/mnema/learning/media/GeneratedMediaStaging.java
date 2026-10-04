package app.mnema.learning.media;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * {@link GeneratedMediaStager} on the upload machinery: reserve the asset and its single-PUT session (one short transaction), write the bytes to
 * the staging key (no transaction), then finalize (copy to the frozen key, seal, the asset becomes {@code VERIFYING}). The finalize command is
 * derived from the asset, so a retry after a crash resumes the same finalization instead of conflicting with it.
 */
@Service
final class GeneratedMediaStaging implements GeneratedMediaStager {
    private final MediaUploadRepository repository;
    private final MediaObjectStore objects;
    private final MediaUploadService uploads;
    private final MediaUploadSettings settings;

    GeneratedMediaStaging(MediaUploadRepository repository, MediaObjectStore objects, MediaUploadService uploads, MediaUploadSettings settings) {
        this.repository = repository;
        this.objects = objects;
        this.uploads = uploads;
        this.settings = settings;
    }

    @Override
    public void stage(UUID owner, UUID assetId, MediaCatalog.Kind kind, String mimeType, byte[] bytes) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(assetId, "assetId");
        String column = kind.column();
        settings.validate(column, mimeType, bytes.length);
        var session = repository.reserveGenerated(owner, assetId, column, mimeType, bytes.length, fingerprint(column, mimeType, bytes));
        if (session.state().equals("SEALED")) return;
        if (session.state().equals("OPEN")) objects.put(session.stagingKey(), bytes, mimeType);
        uploads.finalizeUpload(owner, assetId, session.generation(), command(assetId));
    }

    @Override
    public State assetState(UUID owner, UUID assetId) {
        return repository.assetState(owner, assetId).map(state -> switch (state) {
            case "READY" -> State.READY;
            case "REJECTED" -> State.REJECTED;
            case "FAILED_RETRYABLE", "DELETED" -> State.FAILED;
            default -> State.VERIFYING;
        }).orElse(State.MISSING);
    }

    /** A UUIDv4-shaped command id that is a function of the asset. */
    private static UUID command(UUID assetId) {
        byte[] hash = sha256(("generated-media/" + assetId).getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(hash);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] fingerprint(String kind, String mime, byte[] bytes) {
        byte[] head = sha256(bytes);
        byte[] meta = sha256((kind + "|" + mime + "|" + bytes.length).getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[32];
        for (int index = 0; index < 32; index++) out[index] = (byte) (head[index] ^ meta[index]);
        return out;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
