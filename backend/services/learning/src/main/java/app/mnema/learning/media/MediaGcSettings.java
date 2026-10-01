package app.mnema.learning.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Independent physical retention policy; changing upload or Study limits cannot shorten GC grace. */
@Component
final class MediaGcSettings {
    final boolean enabled;
    final Duration grace;
    final Duration scanGap;
    final Duration deleteLease;
    final Duration retryDelay;
    final int scanBatch;
    final int deleteBatch;

    MediaGcSettings(
            @Value("${learning.media.gc.enabled:false}") boolean enabled,
            @Value("${learning.media.gc.grace:P1D}") Duration grace,
            @Value("${learning.media.gc.scan-gap:PT1H}") Duration scanGap,
            @Value("${learning.media.gc.delete-lease:PT15M}") Duration deleteLease,
            @Value("${learning.media.gc.retry-delay:PT1H}") Duration retryDelay,
            @Value("${learning.media.gc.scan-batch:32}") int scanBatch,
            @Value("${learning.media.gc.delete-batch:4}") int deleteBatch) {
        if (grace.compareTo(Duration.ofDays(1)) < 0 || grace.compareTo(Duration.ofDays(30)) > 0
                || scanGap.compareTo(Duration.ofHours(1)) < 0 || scanGap.compareTo(grace) >= 0
                || deleteLease.compareTo(Duration.ofMinutes(15)) < 0
                || deleteLease.compareTo(Duration.ofHours(1)) > 0
                || retryDelay.compareTo(Duration.ofMinutes(15)) < 0
                || retryDelay.compareTo(Duration.ofDays(1)) > 0
                || scanBatch < 1 || scanBatch > 100 || deleteBatch < 1 || deleteBatch > 20) {
            throw new IllegalArgumentException("Invalid media GC policy");
        }
        this.enabled = enabled;
        this.grace = grace;
        this.scanGap = scanGap;
        this.deleteLease = deleteLease;
        this.retryDelay = retryDelay;
        this.scanBatch = scanBatch;
        this.deleteBatch = deleteBatch;
    }
}
