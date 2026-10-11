package app.mnema.learning.fork;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code learning.community.forks.*}: the kill switch is off by default and a bad value fails the start. */
class ForkSettingsTest {
    @Test
    void validValuesAreKeptAndTheSwitchIsOffByDefault() {
        ForkSettings defaults = new ForkSettings(false, 200, 10);
        assertThat(defaults.enabled).isFalse();
        assertThat(defaults.sliceSize).isEqualTo(200);
        assertThat(defaults.maxPreparing).isEqualTo(10);
        ForkSettings custom = new ForkSettings(true, 10, 100);
        assertThat(custom.enabled).isTrue();
        assertThat(new ForkSettings(false, 1_000, 1).sliceSize).isEqualTo(1_000);
    }

    @Test
    void valuesOutsideTheirBoundsAreRejected() {
        for (int[] bad : new int[][] {{9, 10}, {1_001, 10}, {200, 0}, {200, 101}, {-1, -1}}) {
            assertThatThrownBy(() -> new ForkSettings(true, bad[0], bad[1])).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
