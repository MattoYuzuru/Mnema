package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The retention of sessions, one pass of {@link RetentionWorker}: at {@code expires_at} (last activity plus
 * {@code learning.generation.session-retention}) a live session ends {@code EXPIRED} and stays readable for
 * {@code retention.expired-readable}; then the purge deletes it with everything unpublished (artifacts, revisions, slots,
 * media holds, steps, events) and releases its credit holds, so afterwards every read is a 404. Published materials stay in
 * the catalog, handed-off drafts in the editor, and {@code generation_provenance} stays for audit. {@code CLOSED} and
 * {@code CANCELLED} sessions are purged at their {@code expires_at} directly. Three days before expiry the owner is told how
 * many proposals would be deleted ({@code GENERATION_SESSION_EXPIRING}, once per expiry date). The events of an ended session
 * are deleted a day after it ended. Each session is handled in a short transaction of its own under its row lock, and a
 * condition is re-read under the lock: a session that had activity since it was found is left alone.
 */
@Service
class SessionRetention {
    private static final Logger LOG = LoggerFactory.getLogger(SessionRetention.class);

    /** What one pass did. */
    record Pass(int expired, int purged, int warned, int eventsDeleted) { }

    private final GenerationRepository repository;
    private final SessionLifecycle lifecycle;
    private final NotificationPublisher notifications;
    private final GenerationSettings settings;
    private final TransactionTemplate transaction;

    SessionRetention(GenerationRepository repository, SessionLifecycle lifecycle, NotificationPublisher notifications,
                     GenerationSettings settings, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.notifications = notifications;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
    }

    Pass run() {
        int batch = settings.retention().batch();
        int expired = 0;
        int purged = 0;
        List<UUID> due;
        while (!(due = repository.dueForExpiry(batch)).isEmpty()) {
            int handled = 0;
            for (UUID id : due) handled += expire(id) ? 1 : 0;
            expired += handled;
            if (due.size() < batch || handled == 0) break;
        }
        while (!(due = repository.dueForPurge(settings.retention().expiredReadable(), batch)).isEmpty()) {
            int handled = 0;
            for (UUID id : due) handled += purge(id) ? 1 : 0;
            purged += handled;
            if (due.size() < batch || handled == 0) break;
        }
        int warned = warn();
        int events = repository.deleteOldEvents(settings.retention().eventsAfterEnd(), batch * 100);
        if (expired + purged + warned + events > 0) {
            LOG.info("generation_retention_pass expired={} purged={} warned={} events_deleted={}", expired, purged, warned, events);
        }
        return new Pass(expired, purged, warned, events);
    }

    private boolean expire(UUID sessionId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
            if (tx == null || !live(tx.state) || !repository.expiredNow(sessionId)) return false;
            lifecycle.expire(tx);
            return true;
        }));
    }

    private boolean purge(UUID sessionId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
            if (tx == null || !repository.expiredNow(sessionId)) return false;
            if (tx.state.equals("EXPIRED") && !repository.purgeDueNow(sessionId, settings.retention().expiredReadable())) return false;
            if (live(tx.state)) return false;
            lifecycle.releaseHolds(tx.session);
            repository.deleteSession(sessionId);
            return true;
        }));
    }

    /** The owners of live sessions that expire within the warning window hear what would be deleted; once per expiry date. */
    private int warn() {
        int warned = 0;
        UUID after = new UUID(0, 0);
        while (true) {
            List<Session> page = repository.expiring(settings.retention().warnBefore(), after, settings.retention().batch());
            for (Session session : page) warned += warn(session.sessionId()) ? 1 : 0;
            if (page.size() < settings.retention().batch()) return warned;
            after = page.getLast().sessionId();
        }
    }

    private boolean warn(UUID sessionId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
            if (tx == null || !live(tx.state)) return false;
            int pending = repository.pendingArtifacts(sessionId);
            if (pending == 0) return false;
            Map<String, Object> params = new HashMap<>();
            params.put("deckId", tx.session.deckId());
            params.put("sessionId", sessionId);
            params.put("expiresAt", tx.session.expiresAt());
            params.put("pendingCount", pending);
            String day = tx.session.expiresAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
            return notifications.publish(tx.session.ownerId(), NotificationKind.GENERATION_SESSION_EXPIRING,
                    "generation:" + sessionId + ":expiring:" + day, params, NotificationRoute.WORKSHOP);
        }));
    }

    private static boolean live(String state) {
        return state.equals("PLANNING") || state.equals("PLAN_READY") || state.equals("RUNNING") || state.equals("REVIEW");
    }
}
