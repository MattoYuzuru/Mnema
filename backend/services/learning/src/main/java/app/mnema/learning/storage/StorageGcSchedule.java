package app.mnema.learning.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of {@link StorageGc}. It exists only for the {@code worker} and {@code all} roles ({@code learning.runtime.roles}),
 * like the other workers: an {@code api} process never reclaims on a timer. {@code learning.storage.gc.enabled=false} is the kill
 * switch; passes that overlap across processes are safe (see {@link StorageGc}).
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class StorageGcSchedule {
    private static final Logger log = LoggerFactory.getLogger(StorageGcSchedule.class);

    private final StorageGc gc;
    private final StorageGcSettings settings;

    StorageGcSchedule(StorageGc gc, StorageGcSettings settings) {
        this.gc = gc;
        this.settings = settings;
    }

    @Scheduled(initialDelayString = "${learning.storage.gc.initial-delay:PT2M}",
            fixedDelayString = "${learning.storage.gc.interval:PT1M}")
    void run() {
        if (!settings.enabled) return;
        try {
            gc.runOnce();
        } catch (RuntimeException failure) {
            log.warn("storage_gc_pass_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
