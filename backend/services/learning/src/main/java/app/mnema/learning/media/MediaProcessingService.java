package app.mnema.learning.media;

import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.api.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reconciles sealed generations to ready media without holding a database transaction over I/O. */
@Service
final class MediaProcessingService {
    private static final Logger log = LoggerFactory.getLogger(MediaProcessingService.class);
    private final MediaProcessingRepository repository;
    private final MediaUploadService uploads;
    private final MediaObjectStore objects;
    private final MediaWorkerGateway worker;
    private final MediaGcRepository gc;
    private final MediaProcessingSettings settings;
    private final AtomicInteger active = new AtomicInteger();

    MediaProcessingService(MediaProcessingRepository repository, MediaUploadService uploads,
                           MediaObjectStore objects, MediaWorkerGateway worker, MediaGcRepository gc,
                           MediaProcessingSettings settings) {
        this.repository = repository;
        this.uploads = uploads;
        this.objects = objects;
        this.worker = worker;
        this.gc = gc;
        this.settings = settings;
    }

    @Scheduled(initialDelayString = "${learning.media.processing.initial-delay:PT30S}",
            fixedDelayString = "${learning.media.processing.scan-interval:PT15S}")
    void scan() {
        if (!settings.enabled) return;
        while (true) {
            int current = active.get();
            if (current >= settings.maxParallel) return;
            if (!active.compareAndSet(current, current + 1)) continue;
            Thread.startVirtualThread(() -> {
                try {
                    MediaProcessingRepository.Claim claim;
                    while ((claim = repository.claim()) != null) process(claim);
                } catch (RuntimeException failure) {
                    log.warn("media_processing_scan_deferred error_type={}", failure.getClass().getSimpleName());
                } finally {
                    active.decrementAndGet();
                }
            });
        }
    }

    /** Explicit owner action after retries are exhausted, reusing the sealed original. */
    void retryPreserved(UUID owner, UUID asset, long generation) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(asset, "assetId");
        if (generation < 0) throw new InvalidRequestException();
        repository.retryPreserved(owner, asset, generation);
    }

    void process(MediaProcessingRepository.Claim claim) {
        var alive = new AtomicBoolean(true);
        var lost = new AtomicBoolean(false);
        Thread heartbeat = Thread.startVirtualThread(() -> {
            while (alive.get()) {
                try {
                    Thread.sleep(settings.heartbeat);
                    if (alive.get() && !repository.heartbeat(claim)) {
                        lost.set(true);
                        return;
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException failure) {
                    // A temporary DB failure does not authorize publishing without a valid lease.
                    lost.set(true);
                    return;
                }
            }
        });
        Path job = null;
        try {
            var source = uploads.sealedSource(claim.assetId(), claim.generation());
            if (!source.sessionId().equals(claim.sessionId()) || !source.kind().equals(claim.kind())
                    || source.declaredLength() != claim.declaredLength()) {
                throw new MediaProcessingRejectedException("source_contract_mismatch");
            }
            Files.createDirectories(settings.workRoot);
            job = Files.createTempDirectory(settings.workRoot, "media-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            String sha = objects.downloadVerified(source.objectKey(), job.resolve("source"), claim.declaredLength());
            if (lost.get()) return;
            long maxDuration = settings.maxDurationMs(claim.kind());
            worker.run(job, claim.assetId(), claim.generation(), claim.kind(), claim.declaredLength(), sha,
                    maxDuration);
            var result = MediaWorkerResult.read(job.resolve("output"), claim.assetId(), claim.generation(),
                    claim.kind(), claim.declaredLength(), sha, maxDuration);
            // a PCM WAV source is accepted for what the server synthesised itself (#297), never for a browser upload
            if (result.source().mimeType().equals("audio/wav") && !repository.generated(claim.assetId())) {
                throw new MediaProcessingRejectedException("unsupported_audio");
            }
            if (lost.get()) return;
            var variants = new ArrayList<MediaProcessingRepository.Variant>();
            for (var variant : result.variants()) {
                if (lost.get()) return;
                String key = "derived/" + claim.assetId() + "/" + claim.generation() + "/"
                        + claim.token() + "/" + variant.profile() + "/" + variant.sha256();
                gc.recordDerivedIntent(claim, key);
                objects.putVerified(key, variant.path(), variant.byteLength(), variant.sha256(), variant.mimeType());
                variants.add(new MediaProcessingRepository.Variant(variant.purpose(), variant.profile(),
                        new MediaProcessingRepository.Blob(variant.sha256(), variant.byteLength(),
                                variant.mimeType(), key), variant.width(), variant.height(), variant.durationMs()));
            }
            if (lost.get()) return;
            var sourceBlob = new MediaProcessingRepository.Blob(sha, claim.declaredLength(),
                    result.source().mimeType(), source.objectKey());
            repository.complete(claim, sourceBlob, variants);
        } catch (MediaProcessingRejectedException failure) {
            repository.rejected(claim, failure.code());
            log.info("media_processing_rejected asset_id={} generation={} code={}",
                    claim.assetId(), claim.generation(), failure.code());
        } catch (IOException | IllegalArgumentException | MediaStorageUnavailableException failure) {
            repository.retryable(claim, "processing_unavailable");
            log.warn("media_processing_retryable asset_id={} generation={} error_type={}",
                    claim.assetId(), claim.generation(), failure.getClass().getSimpleName());
        } catch (RuntimeException failure) {
            repository.retryable(claim, "processing_unavailable");
            log.warn("media_processing_retryable asset_id={} generation={} error_type={}",
                    claim.assetId(), claim.generation(), failure.getClass().getSimpleName());
        } finally {
            alive.set(false);
            heartbeat.interrupt();
            if (job != null) deleteJob(job);
        }
    }

    private static void deleteJob(Path job) {
        try (var paths = Files.walk(job)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) {
            log.warn("media_processing_job_cleanup_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
