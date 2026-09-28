package app.mnema.learning.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded physical reclamation: mark in DB, delete in S3, then commit the receipt. */
@Service
final class MediaGcService {
    private static final Logger log = LoggerFactory.getLogger(MediaGcService.class);
    private final MediaCatalog catalog;
    private final MediaGcRepository repository;
    private final MediaObjectStore objects;
    private final MediaGcSettings settings;
    private final AtomicBoolean running = new AtomicBoolean();

    MediaGcService(MediaCatalog catalog, MediaGcRepository repository, MediaObjectStore objects,
                   MediaGcSettings settings) {
        this.catalog = catalog;
        this.repository = repository;
        this.objects = objects;
        this.settings = settings;
    }

    @Scheduled(initialDelayString = "${learning.media.gc.initial-delay:PT5M}",
            fixedDelayString = "${learning.media.gc.scan-interval:PT10M}")
    void scheduled() {
        if (!settings.enabled || !running.compareAndSet(false, true)) return;
        Thread.startVirtualThread(() -> {
            try { runOnce(); }
            catch (RuntimeException failure) {
                log.warn("media_gc_scan_deferred error_type={}", failure.getClass().getSimpleName());
            } finally { running.set(false); }
        });
    }

    void runOnce() {
        int tombstoned = catalog.expireUnattached(settings.scanBatch);
        int discovered = repository.discover(settings.scanBatch);
        int scanned = repository.scan(settings.scanBatch);
        if (tombstoned > 0 || discovered > 0 || scanned > 0) {
            log.info("media_gc_scan tombstoned={} discovered={} checked={}", tombstoned, discovered, scanned);
        }
        for (int index = 0; index < settings.deleteBatch; index++) {
            var claim = repository.claimDeletion();
            if (claim == null) break;
            try {
                objects.delete(claim.key());
                if (repository.completeDeletion(claim)) {
                    log.info("media_gc_deleted token={}", claim.token());
                }
            } catch (MediaStorageUnavailableException failure) {
                repository.deferDeletion(claim, "storage_unavailable");
                log.warn("media_gc_delete_deferred token={} error_type={}",
                        claim.token(), failure.getClass().getSimpleName());
            }
        }
    }
}
