package app.mnema.learning.usage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/** Retention of the derived usage rows: fair-use counters of windows that ended long ago. The ledger is never trimmed here. */
@Service
class UsageMaintenance {
    /** A counter older than this is no longer shown or checked: the longest window is a month. */
    static final Duration COUNTER_RETENTION = Duration.ofDays(90);
    private static final int BATCH = 1_000;

    private final UsageRepository repository;
    private final UsageClock clock;

    UsageMaintenance(UsageRepository repository, UsageClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return how many counters were removed (at most one batch per call) */
    @Transactional
    int purgeOldCounters() {
        return repository.purgeCounters(clock.now().minus(COUNTER_RETENTION), BATCH);
    }
}
