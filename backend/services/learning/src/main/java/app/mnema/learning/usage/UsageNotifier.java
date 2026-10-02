package app.mnema.learning.usage;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a bucket crossing into {@code USAGE_LOW} and {@code USAGE_EXHAUSTED} ({@code contracts/notifications}). It
 * publishes inside the caller's transaction, so the notification exists if and only if the debit or consumption that
 * crossed the threshold commits; a repeat is deduplicated by key, never by a lookup here.
 */
@Component
final class UsageNotifier {
    private final NotificationPublisher publisher;
    private final UsagePolicy policy;

    UsageNotifier(NotificationPublisher publisher, UsagePolicy policy) {
        this.publisher = publisher;
        this.policy = policy;
    }

    /** One bucket window after a write that moved its usage from {@code usedBefore} to {@code usedAfter}. */
    record Crossing(UUID owner, Plan plan, Bucket bucket, Window window, Instant windowStart, String periodId,
                    long usedBefore, long usedAfter, long limit, Instant renewsAt, boolean low) { }

    /**
     * Publishes the highest {@code USAGE_LOW} threshold the write crossed (one notice per write, not one per
     * threshold) and {@code USAGE_EXHAUSTED} when the window just ran out. A limit of 0 is "not offered" and never
     * notifies.
     */
    void publish(Crossing crossing) {
        long limit = crossing.limit();
        if (limit <= 0 || crossing.usedAfter() <= crossing.usedBefore()) return;
        if (crossing.low()) {
            Integer crossed = null;
            for (int threshold : policy.lowThresholds()) {
                if (crossing.usedBefore() * 100 < threshold * limit && crossing.usedAfter() * 100 >= threshold * limit) {
                    crossed = threshold;
                }
            }
            if (crossed != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("bucket", crossing.bucket().name());
                params.put("percent", (int) Math.min(100, crossing.usedAfter() * 100 / limit));
                params.put("unit", crossing.bucket().unit().name());
                params.put("remaining", UsageState.display(crossing.bucket(), Math.max(0, limit - crossing.usedAfter()), false));
                params.put("renewsAt", crossing.renewsAt());
                params.put("plan", crossing.plan().name());
                publisher.publish(crossing.owner(), NotificationKind.USAGE_LOW,
                        "usage:" + crossing.bucket() + ":" + crossing.periodId() + ":low:" + crossed, params,
                        NotificationRoute.PLANS);
            }
        }
        if (crossing.usedBefore() < limit && crossing.usedAfter() >= limit) {
            Map<String, Object> params = new HashMap<>();
            params.put("bucket", crossing.bucket().name());
            params.put("window", crossing.window().name());
            params.put("renewsAt", crossing.renewsAt());
            params.put("plan", crossing.plan().name());
            publisher.publish(crossing.owner(), NotificationKind.USAGE_EXHAUSTED,
                    "usage:" + crossing.bucket() + ":" + crossing.windowStart() + ":exhausted", params,
                    NotificationRoute.PLANS);
        }
    }
}
