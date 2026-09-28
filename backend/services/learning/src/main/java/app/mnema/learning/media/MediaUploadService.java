package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Coordinates durable transfer state with S3 effects; it never holds a DB transaction across S3 I/O. */
@Service
public final class MediaUploadService {
    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);
    private final MediaUploadRepository repository;
    private final MediaObjectStore objects;
    private final MediaUploadSettings settings;

    MediaUploadService(MediaUploadRepository repository, MediaObjectStore objects, MediaUploadSettings settings) {
        this.repository = repository;
        this.objects = objects;
        this.settings = settings;
    }

    public UploadView start(UUID owner, UUID intent, String originValue, String kind, String mime, long length) {
        UuidPolicy.requireEntityId(owner, "owner");
        commandId(intent);
        settings.validate(kind, mime, length);
        MediaCatalog.Origin origin = origin(originValue);
        var session = repository.reserve(owner, intent, origin, kind, mime, length,
                fingerprint(origin, kind, mime, length));
        return prepare(session);
    }

    public UploadView retry(UUID owner, UUID asset, UUID command, String kind, String mime, long length) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(asset, "assetId");
        commandId(command);
        settings.validate(kind, mime, length);
        // Origin is fixed on the stable asset; this fingerprint identifies the replacement transfer.
        var session = repository.retry(owner, asset, command, kind, mime, length,
                fingerprint(null, kind, mime, length));
        return prepare(session);
    }

    public UploadView status(UUID owner, UUID asset) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(asset, "assetId");
        return view(repository.own(owner, asset), null, List.of());
    }

    /** Internal #236 handoff: one immutable source for the current asset generation. */
    public SealedSource sealedSource(UUID asset, long generation) {
        UuidPolicy.requireEntityId(asset, "assetId");
        if (generation < 0) throw new ResourceNotFoundException();
        var source = repository.sealedSource(asset, generation);
        return new SealedSource(source.sessionId(), source.ownerId(), source.kind(),
                source.declaredMime(), source.declaredLength(), source.objectKey());
    }

    public List<Integer> uploadedParts(UUID owner, UUID asset, long generation) {
        var session = repository.own(owner, asset);
        if (session.generation() != generation || !session.method().equals("MULTIPART")
                || !session.state().equals("OPEN")) throw new MediaUploadConflictException();
        return objects.parts(session.stagingKey(), session.storageUploadId()).stream()
                .map(MediaObjectStore.StoredPart::number).toList();
    }

    public UploadView partUrls(UUID owner, UUID asset, long generation, int firstPart, int count) {
        var session = repository.own(owner, asset);
        if (session.generation() != generation || !session.state().equals("OPEN")
                || !session.expiresAt().isAfter(Instant.now())) throw new MediaUploadConflictException();
        if (!session.method().equals("MULTIPART") || firstPart < 1 || count < 1 || count > 16
                || (long) firstPart + count - 1 > settings.partCount(session.length())) {
            throw new InvalidRequestException();
        }
        var urls = new ArrayList<PartUrl>(count);
        Instant latestExpiry = Instant.EPOCH;
        for (int number = firstPart; number < firstPart + count; number++) {
            long bytes = settings.expectedPartLength(session.length(), number);
            var signed = objects.partUrl(session.stagingKey(), session.storageUploadId(), number, bytes);
            urls.add(new PartUrl(number, bytes, signed.url(), signed.headers(), signed.expiresAt()));
            if (signed.expiresAt().isAfter(latestExpiry)) latestExpiry = signed.expiresAt();
        }
        session = repository.issue(owner, asset, generation, latestExpiry);
        return view(session, null, urls);
    }

    public UploadView finalizeUpload(UUID owner, UUID asset, long generation, UUID command) {
        commandId(command);
        var claim = repository.claim(owner, asset, generation, command);
        var session = claim.session();
        if (!claim.acquired()) return view(session, null, List.of());
        if (session.method().equals("SINGLE")) finalizeSingle(session, command);
        else finalizeMultipart(session, command);
        repository.seal(session.sessionId(), command);
        return view(repository.own(owner, asset), null, List.of());
    }

    public UploadView cancel(UUID owner, UUID asset, long generation) {
        var session = repository.cancel(owner, asset, generation);
        if (session.method().equals("MULTIPART") && session.storageUploadId() != null) {
            try { objects.abort(session.stagingKey(), session.storageUploadId()); }
            catch (MediaStorageUnavailableException failure) {
                // Durable cleanup retries after URLs expire.
                log.warn("media_upload_abort_deferred session_id={}", session.sessionId());
            }
        }
        return view(session, null, List.of());
    }

    private UploadView prepare(MediaUploadRepository.Session session) {
        if (repository.own(session.ownerId(), session.assetId()).generation() != session.generation()) {
            return view(session, null, List.of());
        }
        if (session.state().equals("INITIATING") && repository.claimInitiation(session.sessionId())) {
            String storageUpload;
            try { storageUpload = objects.createMultipart(session.stagingKey()); }
            catch (MediaStorageUnavailableException failure) {
                repository.releaseInitiation(session.sessionId());
                throw failure;
            }
            try { session = repository.open(session.sessionId(), storageUpload); }
            catch (MediaUploadConflictException failure) {
                objects.abort(session.stagingKey(), storageUpload);
                throw failure;
            }
        }
        if (session.state().equals("OPEN") && session.method().equals("SINGLE")) {
            var signed = objects.singleUrl(session.stagingKey(), session.length());
            session = repository.issue(session.ownerId(), session.assetId(), session.generation(), signed.expiresAt());
            return view(session, signed, List.of());
        }
        return view(session, null, List.of());
    }

    private void finalizeSingle(MediaUploadRepository.Session session, UUID command) {
        var frozen = objects.head(session.frozenKey());
        if (frozen == null) {
            if (session.copyStartedAt() != null) {
                repository.failUncertainCopy(session.sessionId(), command);
                throw new MediaUploadConflictException();
            }
            var source = objects.head(session.stagingKey());
            if (source == null) {
                repository.releaseIncomplete(session.sessionId(), command);
                throw new MediaUploadConflictException();
            }
            if (source.size() != session.length()) {
                repository.releaseIncomplete(session.sessionId(), command);
                throw new MediaUploadConflictException();
            }
            // A timed-out S3 copy may still complete remotely. Never issue a second copy to
            // this key: a late first copy could otherwise replace bytes after verification starts.
            if (!repository.claimCopy(session.sessionId(), command)) {
                repository.failUncertainCopy(session.sessionId(), command);
                throw new MediaUploadConflictException();
            }
            try { objects.freeze(session.stagingKey(), session.frozenKey(), source.eTag()); }
            catch (MediaUploadConflictException failure) {
                repository.rejectedCopyPrecondition(session.sessionId(), command);
                throw failure;
            }
            frozen = objects.head(session.frozenKey());
        }
        if (frozen == null || frozen.size() != session.length()) throw new MediaStorageUnavailableException();
    }

    private void finalizeMultipart(MediaUploadRepository.Session session, UUID command) {
        var completed = objects.head(session.stagingKey());
        if (completed == null) {
            var parts = objects.parts(session.stagingKey(), session.storageUploadId());
            int expectedCount = settings.partCount(session.length());
            if (parts.size() != expectedCount) {
                repository.releaseIncomplete(session.sessionId(), command);
                throw new MediaUploadConflictException();
            }
            for (int index = 0; index < parts.size(); index++) {
                var part = parts.get(index);
                if (part.number() != index + 1 || part.size() != settings.expectedPartLength(session.length(), index + 1)) {
                    repository.releaseIncomplete(session.sessionId(), command);
                    throw new MediaUploadConflictException();
                }
            }
            try { objects.complete(session.stagingKey(), session.storageUploadId(), parts); }
            catch (MediaUploadConflictException failure) {
                repository.releaseIncomplete(session.sessionId(), command);
                throw failure;
            }
            completed = objects.head(session.stagingKey());
        }
        if (completed == null || completed.size() != session.length()) throw new MediaStorageUnavailableException();
    }

    /** Bounded, retryable cleanup; verified blobs are governed by the separate reachability GC. */
    @Scheduled(initialDelayString = "${learning.media.upload.cleanup-initial-delay:PT1M}",
            fixedDelayString = "${learning.media.upload.cleanup-interval:PT5M}")
    public void cleanup() {
        for (var session : repository.stalledFinalizations(20)) {
            try { finalizeUpload(session.ownerId(), session.assetId(), session.generation(), session.finalizeCommandId()); }
            catch (RuntimeException failure) {
                log.warn("media_upload_finalize_reconcile_deferred session_id={} error_type={}",
                        session.sessionId(), failure.getClass().getSimpleName());
            }
        }
        for (var session : repository.cleanupCandidates(50)) {
            try {
                if (session.method().equals("MULTIPART") && session.storageUploadId() != null
                        && !session.state().equals("SEALED")) {
                    objects.abort(session.stagingKey(), session.storageUploadId());
                }
                if (!session.state().equals("SEALED")) objects.delete(session.sealedKey());
                if (session.method().equals("SINGLE") || !session.state().equals("SEALED")) {
                    objects.delete(session.stagingKey());
                }
                repository.cleaned(session.sessionId());
            } catch (MediaStorageUnavailableException failure) {
                log.warn("media_upload_cleanup_deferred session_id={}", session.sessionId());
            }
        }
    }

    private UploadView view(MediaUploadRepository.Session session, MediaObjectStore.SignedUrl signed,
                            List<PartUrl> parts) {
        var asset = repository.assetStatus(session.ownerId(), session.assetId());
        return new UploadView(session.assetId(), session.generation(), session.state(),
                asset.generation(), asset.state(), session.method(),
                session.length(), session.mime(), session.expiresAt(),
                session.method().equals("MULTIPART") ? settings.partSize : null,
                session.method().equals("MULTIPART") ? settings.partCount(session.length()) : null,
                signed == null ? null : signed.url(), signed == null ? Map.of() : signed.headers(),
                signed == null ? null : signed.expiresAt(), parts);
    }

    private static MediaCatalog.Origin origin(String value) {
        try { return MediaCatalog.Origin.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (NullPointerException | IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }

    private static void commandId(UUID value) {
        try { UuidPolicy.requireCommandId(value); }
        catch (RuntimeException failure) { throw new InvalidRequestException(); }
    }

    private static byte[] fingerprint(MediaCatalog.Origin origin, String kind, String mime, long length) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            sha.update((origin == null ? "retry" : origin.name()).getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(kind.getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(mime.getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
            return sha.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record UploadView(UUID assetId, long generation, String state, long currentGeneration,
                             String assetState, String method, long declaredLength,
                             String declaredMime, Instant expiresAt, Long partSize, Integer partCount,
                             String url, Map<String, String> headers, Instant urlExpiresAt, List<PartUrl> parts) { }
    public record PartUrl(int number, long length, String url, Map<String, String> headers,
                          Instant expiresAt) { }
    public record SealedSource(UUID sessionId, UUID ownerId, String kind, String declaredMime,
                               long declaredLength, String objectKey) { }
}
