package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiJobStepEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobStepStatus;
import app.mnema.ai.repository.AiJobRepository;
import app.mnema.ai.repository.AiJobStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJobExecutionServiceTest {

    @Mock
    private AiJobStepRepository stepRepository;

    @Mock
    private AiJobRepository jobRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AiJobExecutionService service;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Object> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        service = new AiJobExecutionService(stepRepository, jobRepository, transactionTemplate);
    }

    @Test
    void updateStepProgressUsesWeightedFractionForCurrentStep() {
        UUID jobId = UUID.randomUUID();
        AiJobEntity job = new AiJobEntity();
        job.setJobId(jobId);
        job.setStatus(AiJobStatus.processing);
        job.setProgress(1);
        job.setUpdatedAt(Instant.now().minusSeconds(30));

        when(stepRepository.findByJobIdOrderByStartedAtAscStepNameAsc(jobId)).thenReturn(List.of(
                new AiJobStepEntity(jobId, "prepare_context", AiJobStepStatus.completed, Instant.now().minusSeconds(20), Instant.now().minusSeconds(15), null),
                new AiJobStepEntity(jobId, "generate_content", AiJobStepStatus.processing, Instant.now().minusSeconds(10), null, null),
                new AiJobStepEntity(jobId, "generate_audio", AiJobStepStatus.queued, null, null, null),
                new AiJobStepEntity(jobId, "apply_changes", AiJobStepStatus.queued, null, null, null)
        ));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(AiJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateStepProgress(jobId, "generate_content", 0.5d);

        assertThat(job.getProgress()).isEqualTo(40);
        assertThat(job.getUpdatedAt()).isNotNull();
        verify(jobRepository).save(job);
    }

    @Test
    void updateStepProgressSkipsTerminalJobs() {
        UUID jobId = UUID.randomUUID();
        AiJobEntity job = new AiJobEntity();
        job.setJobId(jobId);
        job.setStatus(AiJobStatus.completed);
        job.setProgress(88);

        when(stepRepository.findByJobIdOrderByStartedAtAscStepNameAsc(jobId)).thenReturn(List.of(
                new AiJobStepEntity(jobId, "generate_content", AiJobStepStatus.processing, Instant.now().minusSeconds(10), null, null)
        ));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        service.updateStepProgress(jobId, "generate_content", 0.8d);

        assertThat(job.getProgress()).isEqualTo(88);
        verify(jobRepository, never()).save(job);
    }
}
