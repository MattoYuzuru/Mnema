package app.mnema.learning.usage;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The dedupe keys of usage notifications: a window instance is named by its kind as well as its start. */
class UsageNotifierTest {
    @Test
    void windowsThatStartAtTheSameInstantHaveDifferentExhaustionKeys() {
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        var notifier = new UsageNotifier(publisher, new UsagePolicy("rc-v1", "Europe/Moscow", new BigDecimal("0.35"),
                "13,13,12,12", 80, Duration.ofHours(2)));
        Instant firstOfMonth = Instant.parse("2026-09-30T21:00:00Z");
        UUID owner = UUID.randomUUID();
        for (Window window : Window.values()) {
            notifier.publish(new UsageNotifier.Crossing(owner, Plan.FREE, Bucket.ASSESSMENT, window, firstOfMonth, "2026-10",
                    4, 5, 5, firstOfMonth.plusSeconds(86_400), false));
        }

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(publisher, atLeastOnce()).publish(eq(owner), eq(NotificationKind.USAGE_EXHAUSTED), keys.capture(), any(),
                eq(NotificationRoute.PLANS));
        assertThat(keys.getAllValues()).containsExactly(
                "usage:ASSESSMENT:DAY:2026-09-30T21:00:00Z:exhausted", "usage:ASSESSMENT:WEEK:2026-09-30T21:00:00Z:exhausted",
                "usage:ASSESSMENT:MONTH:2026-09-30T21:00:00Z:exhausted");
    }
}
