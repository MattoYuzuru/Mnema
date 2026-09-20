package app.mnema.learning.study.retention;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudyRetentionWorkerTest {
    @Test
    void scheduledTickRunsOneBoundedBatch() {
        StudyRetentionService service = mock(StudyRetentionService.class);
        when(service.purgeBatch()).thenReturn(new StudyRetentionService.PurgeResult(1, 2));
        new StudyRetentionWorker(service).purge();
        verify(service).purgeBatch();
    }
}
