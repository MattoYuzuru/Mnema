package app.mnema.learning.fork;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code learning.community.forks.*}: the kill switch is off by default and a bad value fails the start. */
class ForkSettingsTest {
    @Test
    void validValuesAreKeptAndTheSwitchIsOffByDefault() {
        ForkSettings defaults = new ForkSettings(false, 200, 10, 20, java.time.Duration.ofHours(1));
        assertThat(defaults.enabled).isFalse();
        assertThat(defaults.sliceSize).isEqualTo(200);
        assertThat(defaults.maxPreparing).isEqualTo(10);
        assertThat(defaults.maxPerWindow).isEqualTo(20);
        assertThat(defaults.window).isEqualTo(java.time.Duration.ofHours(1));
        ForkSettings custom = new ForkSettings(true, 10, 100, 1_000, java.time.Duration.ofDays(7));
        assertThat(custom.enabled).isTrue();
        assertThat(new ForkSettings(false, 1_000, 1, 1, java.time.Duration.ofMinutes(1)).sliceSize).isEqualTo(1_000);
    }

    @Test
    void valuesOutsideTheirBoundsAreRejected() {
        java.time.Duration hour = java.time.Duration.ofHours(1);
        for (int[] bad : new int[][] {{9, 10, 20}, {1_001, 10, 20}, {200, 0, 20}, {200, 101, 20}, {200, 10, 0}, {200, 10, 1_001}, {-1, -1, -1}}) {
            assertThatThrownBy(() -> new ForkSettings(true, bad[0], bad[1], bad[2], hour)).isInstanceOf(IllegalArgumentException.class);
        }
        for (java.time.Duration bad : new java.time.Duration[] {java.time.Duration.ofSeconds(59), java.time.Duration.ofDays(7).plusSeconds(1), java.time.Duration.ZERO}) {
            assertThatThrownBy(() -> new ForkSettings(true, 200, 10, 20, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
