package app.mnema.learning.storage;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static app.mnema.learning.storage.StorageTypes.CollectionResult;

/**
 * One bounded reclamation pass over {@link ImmutableStorage}: expire lapsed staging pins, then collect unreachable objects whose
 * grace has passed. Reachability (incoming edge or any pin) is decided only by the kernel, row-locked per object, so this class
 * can neither widen nor bypass it.
 *
 * <p><b>Bounds.</b> At most {@code max-scopes} reuse scopes per pass, {@code max-batches-per-scope} kernel batches (eight objects
 * each) per scope and {@code max-run} wall time. Scopes are visited round-robin from a cursor that survives between passes, so one
 * busy or failing scope delays the others by at most one pass; a backlog drains over several passes.
 *
 * <p><b>Overlap.</b> Two passes (two processes, or a slow pass and the next) are safe: the kernel claims work with
 * {@code FOR UPDATE SKIP LOCKED}, so they take disjoint candidates; the cost of overlap is only duplicated probing.
 */
@Service
class StorageGc {
    private static final Logger log = LoggerFactory.getLogger(StorageGc.class);
    private static final int KERNEL_BATCH = 8;

    private final ImmutableStorage storage;
    private final StorageGcRepository repository;
    private final StorageGcSettings settings;
    private final MeterRegistry meters;
    private UUID cursor = StorageGcRepository.START;

    StorageGc(ImmutableStorage storage, StorageGcRepository repository, StorageGcSettings settings, MeterRegistry meters) {
        this.storage = storage;
        this.repository = repository;
        this.settings = settings;
        this.meters = meters;
    }

    record Pass(int scopes, int stagingExpired, int inspected, int deleted, int deferred, int errors) {
    }

    synchronized Pass runOnce() {
        long started = System.nanoTime();
        long deadline = started + settings.maxRun.toNanos();
        List<UUID> visited = new ArrayList<>();
        int expired = 0, inspected = 0, deleted = 0, deferred = 0, errors = 0;
        while (visited.size() < settings.maxScopes && System.nanoTime() - deadline < 0) {
            Instant now = Instant.now();
            Optional<UUID> next = repository.nextScope(cursor, now.minus(settings.grace), now);
            if (next.isEmpty() && !cursor.equals(StorageGcRepository.START)) {
                cursor = StorageGcRepository.START;
                next = repository.nextScope(cursor, now.minus(settings.grace), now);
            }
            if (next.isEmpty() || visited.contains(next.get())) break;
            UUID scope = next.get();
            visited.add(scope);
            cursor = scope;
            try {
                for (int batch = 0; batch < settings.maxBatchesPerScope && System.nanoTime() - deadline < 0; batch++) {
                    Instant at = Instant.now();
                    int lapsed = storage.expireStaging(scope, at, KERNEL_BATCH);
                    CollectionResult result = storage.collectBatch(scope, at.minus(settings.grace), KERNEL_BATCH);
                    expired += lapsed;
                    inspected += result.inspected();
                    deleted += result.deleted();
                    deferred += result.deferred();
                    if (lapsed < KERNEL_BATCH && result.inspected() < KERNEL_BATCH) break;
                }
            } catch (RuntimeException failure) {
                errors++;
                log.warn("storage_gc_scope_failed scope={} error_type={}", scope, failure.getClass().getSimpleName());
            }
        }
        Pass pass = new Pass(visited.size(), expired, inspected, deleted, deferred, errors);
        record(pass, System.nanoTime() - started);
        return pass;
    }

    private void record(Pass pass, long elapsedNanos) {
        meters.counter("mnema_storage_gc_objects_total", "outcome", "inspected").increment(pass.inspected());
        meters.counter("mnema_storage_gc_objects_total", "outcome", "deleted").increment(pass.deleted());
        meters.counter("mnema_storage_gc_objects_total", "outcome", "deferred").increment(pass.deferred());
        meters.counter("mnema_storage_gc_staging_expired_total").increment(pass.stagingExpired());
        meters.counter("mnema_storage_gc_errors_total").increment(pass.errors());
        meters.timer("mnema_storage_gc_pass_seconds").record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (pass.inspected() != 0 || pass.stagingExpired() != 0 || pass.errors() != 0) {
            log.info("storage_gc_pass scopes={} staging_expired={} inspected={} deleted={} deferred={} errors={} elapsed_ms={}",
                    pass.scopes(), pass.stagingExpired(), pass.inspected(), pass.deleted(), pass.deferred(), pass.errors(),
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        }
    }
}
