package app.mnema.learning.media;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploadSettingsTest {
    private static final long MIB = 1024L * 1024;

    @Test
    void browserPolicyReflectsIndependentPerKindServerLimits() {
        var settings = settings(64 * MIB, 512 * MIB, 4_096 * MIB, 8_192 * MIB);
        assertThat(settings.clientPolicy()).isEqualTo(new MediaUploadSettings.ClientPolicy(64 * MIB,
                512 * MIB, 4_096 * MIB));
        settings.validate("video", "video/quicktime", 4_096 * MIB);
        assertThatThrownBy(() -> settings.validate("video", "video/quicktime", 4_096 * MIB + 1))
                .isInstanceOf(app.mnema.learning.platform.api.ResourceLimitExceededException.class);
    }

    @Test
    void rejectsVideoCapAboveReservedOwnerQuota() {
        assertThatThrownBy(() -> settings(64 * MIB, 512 * MIB, 8_193 * MIB, 8_192 * MIB))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MediaUploadSettings settings(long image, long audio, long video, long reserved) {
        return new MediaUploadSettings(URI.create("https://storage.yandexcloud.net"), "ru-central1", "", "", "",
                false, image, audio, video, reserved, 3,
                5_242_880, 5_242_880, Duration.ofMinutes(15), Duration.ofHours(24),
                Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(15));
    }
}
