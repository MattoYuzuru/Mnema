package app.mnema.learning.catalog.authoring;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringSettingsTest {
    @Test
    void scopedPoliciesHaveIndependentValuesAndFailFastOutsideBounds() {
        var settings = new AuthoringSettings(Duration.ofDays(14), 300, 30L * 1024 * 1024,
                20_000, 80L * 1024 * 1024);
        assertThat(settings.draftRecoveryWindow()).isEqualTo(Duration.ofDays(14));
        assertThat(settings.maxActiveDrafts()).isEqualTo(300);
        assertThat(settings.maxDraftBytesPerAccount()).isEqualTo(30L * 1024 * 1024);
        assertThat(settings.maxActiveCaptureNotes()).isEqualTo(20_000);
        assertThat(settings.maxCaptureBytesPerAccount()).isEqualTo(80L * 1024 * 1024);
        assertThatThrownBy(() -> new AuthoringSettings(Duration.ZERO, 300, 30L * 1024 * 1024,
                20_000, 80L * 1024 * 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthoringSettings(Duration.ofDays(14), 1_001, 30L * 1024 * 1024,
                20_000, 80L * 1024 * 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthoringSettings(Duration.ofDays(14), 300, 30L * 1024 * 1024,
                20_000, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
