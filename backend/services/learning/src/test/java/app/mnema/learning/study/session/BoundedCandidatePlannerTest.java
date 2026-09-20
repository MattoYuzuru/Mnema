package app.mnema.learning.study.session;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedCandidatePlannerTest {
    @ParameterizedTest
    @ValueSource(ints = {1_000, 10_000, 50_000})
    void largeDeckFixturesKeepEveryRequestBounded(int candidateCount) {
        BoundedCandidatePlanner.Window window = BoundedCandidatePlanner.window(Long.MIN_VALUE,
                candidateCount, 100);

        assertThat(window.start()).isBetween(0, candidateCount - 1);
        assertThat(window.target()).isEqualTo(20);
        assertThat(window.scanLimit()).isEqualTo(80);
        assertThat(BoundedCandidatePlanner.PREPARATION_LIMIT).isEqualTo(500);
    }
}
