package app.mnema.learning.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Owner-scoped recovery window for a verified asset that has not been attached to content. */
@Component
public final class MediaSettings {
    private final Duration unattachedReadyHold;

    public MediaSettings(@Value("${learning.media.unattached-ready-hold:P7D}") Duration unattachedReadyHold) {
        if (unattachedReadyHold == null || unattachedReadyHold.compareTo(Duration.ofDays(1)) < 0
                || unattachedReadyHold.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid unattached media hold");
        }
        this.unattachedReadyHold = unattachedReadyHold;
    }

    public Duration unattachedReadyHold() {
        return unattachedReadyHold;
    }
}
