package app.mnema.learning.notification;

import java.util.Map;
import java.util.UUID;

/**
 * The producer port of the notification center. There is no outbox and no separate delivery: a producer calls
 * {@link #publish} <strong>inside the transaction of the domain change</strong> it reports, so the notification exists
 * if and only if that change commits. Calling it without a transaction is a programming error and fails.
 *
 * <p>Call it as the last write of the transaction: it takes the owner's cursor-row lock to allocate {@code seq}, and the
 * lock is held until the caller commits, so work after it would lengthen every other publisher's wait.
 */
public interface NotificationPublisher {
    /**
     * Records one notification for {@code owner}.
     *
     * @param dedupeKey stable key from {@code contracts/notifications}; an existing {@code (owner, dedupeKey)} makes this
     *                  a no-op (the first notification stands and is never refreshed) and consumes no {@code seq}
     * @param params    exactly the params of {@code kind}: identifiers, counts, enum tokens and timestamps, never prose
     * @param route     must be the route {@link NotificationKind#route(Map)} gives for these params
     * @return {@code true} when a notification was created, {@code false} for a deduplicated repeat
     * @throws IllegalArgumentException for params or a route that violate the kind (a producer bug)
     * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is active
     */
    boolean publish(UUID owner, NotificationKind kind, String dedupeKey, Map<String, ?> params, NotificationRoute route);
}
