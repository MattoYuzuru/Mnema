package app.mnema.learning.generation;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The eviction sweep drains in batches and never lets a failure escape the scheduler. */
class SpeechCacheSweepTest {
    @Test
    void itEvictsInBatchesUntilTheLastOneIsShortAndSwallowsFailures() {
        SpeechCache cache = mock(SpeechCache.class);
        when(cache.evict(500)).thenReturn(500, 500, 3);
        SpeechCacheSweep sweep = new SpeechCacheSweep(cache);

        sweep.sweep();

        verify(cache, times(3)).evict(500);

        when(cache.evict(500)).thenThrow(new IllegalStateException("db down"));
        sweep.sweep();
        verify(cache, times(4)).evict(500);
    }

    @Test
    void theSlotStepsAreTheImageSearchAndSpeechStepsWithoutATurn() {
        var plain = Json.object();
        var turn = Json.object().put("turnId", "x");
        org.assertj.core.api.Assertions.assertThat(MediaSteps.isSlotStep("TTS", plain)).isTrue();
        org.assertj.core.api.Assertions.assertThat(MediaSteps.isSlotStep("IMAGE_SEARCH", plain)).isTrue();
        org.assertj.core.api.Assertions.assertThat(MediaSteps.isSlotStep("TTS", turn)).isFalse();
        org.assertj.core.api.Assertions.assertThat(MediaSteps.isSlotStep("EDIT", plain)).isFalse();
    }
}
