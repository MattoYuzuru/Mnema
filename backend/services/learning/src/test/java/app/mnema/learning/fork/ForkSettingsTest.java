package app.mnema.learning.fork;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code learning.community.forks.*}: the kill switches are off by default, creation needs both of them, and a bad value fails the start. */
class ForkSettingsTest {
    @Test
    void validValuesAreKeptAndTheSwitchIsOffByDefault() {
        ForkSettings defaults = new ForkSettings(false, false, 200, 10, 20, 200, java.time.Duration.ofHours(1));
        assertThat(defaults.enabled).isFalse();
        assertThat(defaults.sliceSize).isEqualTo(200);
        assertThat(defaults.maxPreparing).isEqualTo(10);
        assertThat(defaults.maxPerWindow).isEqualTo(20);
        assertThat(defaults.window).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(defaults.maxUnfinishedTotal).isEqualTo(200);
        assertThat(defaults.publicRoutes).isFalse();
        ForkSettings custom = new ForkSettings(true, true, 10, 100, 1_000, 100_000, java.time.Duration.ofDays(7));
        assertThat(custom.enabled).isTrue();
        assertThat(custom.maxUnfinishedTotal).isEqualTo(100_000);
        assertThat(new ForkSettings(false, false, 1_000, 1, 1, 1, java.time.Duration.ofMinutes(1)).sliceSize).isEqualTo(1_000);
    }

    @Test
    void creationNeedsTheForkSwitchAndThePublicRoutesSwitch() {
        java.time.Duration hour = java.time.Duration.ofHours(1);
        assertThat(new ForkSettings(true, true, 200, 10, 20, 200, hour).creation()).isTrue();
        assertThat(new ForkSettings(true, false, 200, 10, 20, 200, hour).creation()).as("forks on, public routes off").isFalse();
        assertThat(new ForkSettings(false, true, 200, 10, 20, 200, hour).creation()).as("forks off, public routes on").isFalse();
        assertThat(new ForkSettings(false, false, 200, 10, 20, 200, hour).creation()).isFalse();
    }

    @Test
    void valuesOutsideTheirBoundsAreRejected() {
        java.time.Duration hour = java.time.Duration.ofHours(1);
        for (int[] bad : new int[][] {{9, 10, 20}, {1_001, 10, 20}, {200, 0, 20}, {200, 101, 20}, {200, 10, 0}, {200, 10, 1_001}, {-1, -1, -1}}) {
            assertThatThrownBy(() -> new ForkSettings(true, true, bad[0], bad[1], bad[2], 200, hour)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int bad : new int[] {0, -1, 100_001}) {
            assertThatThrownBy(() -> new ForkSettings(true, true, 200, 10, 20, bad, hour)).isInstanceOf(IllegalArgumentException.class);
        }
        for (java.time.Duration bad : new java.time.Duration[] {java.time.Duration.ofSeconds(59), java.time.Duration.ofDays(7).plusSeconds(1), java.time.Duration.ZERO}) {
            assertThatThrownBy(() -> new ForkSettings(true, true, 200, 10, 20, 200, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
