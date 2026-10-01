package app.mnema.learning.media;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaManifestSettingsTest {
    @Test
    void rejectsUnboundedRetentionAndInventory() {
        assertThatThrownBy(() -> new MediaManifestSettings(Duration.ZERO, 100, 10, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaManifestSettings(Duration.ofDays(366), 100, 10, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaManifestSettings(Duration.ofDays(90), 100, 101, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaManifestSettings(Duration.ofDays(90), 100, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
