package app.mnema.learning.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes expired notifications. The per-account cap needs no sweep: every publication evicts everything beyond
 * the newest {@code max-per-account} sequence slots of its owner, so a lowered cap converges on the next publication.
 */
@Service
class NotificationRetentionService {
    static final int BATCH_SIZE = 500;
    private final NotificationRepository repository;

    NotificationRetentionService(NotificationRepository repository) { this.repository = repository; }

    /** One bounded batch; the worker repeats while batches are full. */
    @Transactional(timeout = 10)
    int purgeBatch() { return repository.purgeExpired(BATCH_SIZE); }
}
