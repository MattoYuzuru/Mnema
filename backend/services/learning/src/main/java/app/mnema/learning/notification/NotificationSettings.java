package app.mnema.learning.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Retention policy of the notification center; changes apply to notifications published afterwards. */
@Component
final class NotificationSettings {
    final Duration retention;
    final int maxPerAccount;
    final int paramsMaxBytes;

    NotificationSettings(@Value("${learning.notifications.retention:P30D}") Duration retention,
                         @Value("${learning.notifications.max-per-account:200}") int maxPerAccount,
                         @Value("${learning.notifications.params-max-bytes:4096}") int paramsMaxBytes) {
        if (retention.compareTo(Duration.ofDays(1)) < 0 || retention.compareTo(Duration.ofDays(90)) > 0
                || retention.getNano() != 0
                || maxPerAccount < 10 || maxPerAccount > 1000
                // The table also checks 4096, so a larger value could never be stored.
                || paramsMaxBytes < 256 || paramsMaxBytes > 4096) {
            throw new IllegalArgumentException("Invalid notification policy");
        }
        this.retention = retention;
        this.maxPerAccount = maxPerAccount;
        this.paramsMaxBytes = paramsMaxBytes;
    }
}
