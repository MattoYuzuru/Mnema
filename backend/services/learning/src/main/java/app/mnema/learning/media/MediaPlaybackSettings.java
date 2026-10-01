package app.mnema.learning.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Short-lived, owner-authorized playback links; independent of upload URL lifetime. */
@Component
public final class MediaPlaybackSettings {
    private final Duration urlTtl;

    public MediaPlaybackSettings(@Value("${learning.media.playback.url-ttl:PT1H}") Duration urlTtl) {
        if (urlTtl == null || urlTtl.compareTo(Duration.ofMinutes(5)) < 0
                || urlTtl.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("Invalid media playback URL lifetime");
        }
        this.urlTtl = urlTtl;
    }

    public Duration urlTtl() { return urlTtl; }
}
