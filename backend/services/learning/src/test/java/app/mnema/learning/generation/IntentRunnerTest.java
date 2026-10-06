package app.mnema.learning.generation;

import app.mnema.learning.ai.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentRunnerTest {
    @Test
    void aTransientClaimFailureDoesNotLeakTheLastWorkerPermit() {
        IntentQueue queue = mock(IntentQueue.class);
        AiProperties ai = mock(AiProperties.class);
        when(ai.permits()).thenReturn(new AiProperties.Permits(1, 1, 1, 1, 1, 1, 1, Duration.ZERO));
        when(queue.claim()).thenThrow(new IllegalStateException("database unavailable")).thenReturn(Optional.empty());
        IntentRunner runner = new IntentRunner(queue, mock(IntentService.class), ai);
        try {
            runner.sweep();
            runner.sweep();
            verify(queue, times(2)).claim();
        } finally {
            runner.destroy();
        }
    }
}
