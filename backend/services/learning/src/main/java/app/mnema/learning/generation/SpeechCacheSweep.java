package app.mnema.learning.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Evicts speech cache entries unused for {@code learning.ai.tts.cache-ttl} ({@link SpeechCache#evict}). It only drops cache rows: the blobs they kept
 * reachable are then reclaimed by the media GC like any other unreferenced blob. Worker and all roles only.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class SpeechCacheSweep {
    private static final Logger LOG = LoggerFactory.getLogger(SpeechCacheSweep.class);
    private static final int BATCH = 500;
    private final SpeechCache cache;

    SpeechCacheSweep(SpeechCache cache) {
        this.cache = cache;
    }

    @Scheduled(initialDelayString = "${learning.ai.tts.sweep-interval:PT1H}", fixedDelayString = "${learning.ai.tts.sweep-interval:PT1H}")
    void sweep() {
        try {
            int deleted;
            do {
                deleted = cache.evict(BATCH);
                if (deleted > 0) LOG.info("speech_cache_evicted count={}", deleted);
            } while (deleted == BATCH);
        } catch (RuntimeException failure) {
            LOG.warn("speech_cache_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
