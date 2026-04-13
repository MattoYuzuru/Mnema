package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJobWorkerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AiJobRepository jobRepository;

    @Mock
    private AiJobProcessor jobProcessor;

    @Mock
    private AiUsageLedgerService usageLedgerService;

    @Mock
    private AiJobCostEstimator costEstimator;

    @Mock
    private AiJobCancellationRegistry cancellationRegistry;

    @Test
    void claimNextJobReturnsEmptyWhenNothingClaimedAndEntityWhenFound() {
        AiJobWorker worker = new AiJobWorker(jdbcTemplate, jobRepository, jobProcessor, usageLedgerService, costEstimator, cancellationRegistry, "worker-1", 300, 3, 1000, 8000, 1);
        UUID jobId = UUID.randomUUID();
        AiJobEntity job = queuedJob(jobId);

        when(jdbcTemplate.query(any(String.class), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(), any()))
                .thenReturn(null)
                .thenReturn(jobId);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThat(worker.claimNextJob()).isEmpty();
        assertThat(worker.claimNextJob()).contains(job);
    }

    @Test
    void markCompletedStoresSummaryAndUsage() {
        AiJobWorker worker = new AiJobWorker(jdbcTemplate, jobRepository, jobProcessor, usageLedgerService, costEstimator, cancellationRegistry, "worker-1", 300, 3, 1000, 8000, 1);
        AiJobEntity job = queuedJob(UUID.randomUUID());
        job.setStatus(AiJobStatus.processing);
        job.setLockedAt(Instant.now());
        job.setLockedBy("worker-1");
        job.setInputHash("hash-1");

        AiJobProcessingResult result = new AiJobProcessingResult(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("ok", true),
                "openai",
                "gpt-4o",
                12,
                34,
                BigDecimal.ONE,
                "prompt-hash"
        );

        when(costEstimator.estimateRecordedCost(job, result)).thenReturn(BigDecimal.ONE);

        worker.markCompleted(job, result);

        assertThat(job.getStatus()).isEqualTo(AiJobStatus.completed);
        assertThat(job.getProgress()).isEqualTo(100);
        assertThat(job.getLockedAt()).isNull();
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getCompletedAt()).isNotNull();
        verify(usageLedgerService).recordUsage(job.getRequestId(), job.getJobId(), job.getUserId(), 12, 34, BigDecimal.ONE, "openai", "gpt-4o", "prompt-hash");
        verify(jobRepository).save(job);
    }

    @Test
    void markFailedRetriesThenFailsPermanently() {
        AiJobWorker worker = new AiJobWorker(jdbcTemplate, jobRepository, jobProcessor, usageLedgerService, costEstimator, cancellationRegistry, "worker-1", 300, 2, 1000, 8000, 1);
        AiJobEntity job = queuedJob(UUID.randomUUID());
        job.setAttempts(0);

        worker.markFailed(job, new IllegalStateException("boom"));
        assertThat(job.getStatus()).isEqualTo(AiJobStatus.queued);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextRunAt()).isNotNull();
        assertThat(job.getErrorMessage()).isEqualTo("IllegalStateException");

        worker.markFailed(job, new IllegalArgumentException("still boom"));
        assertThat(job.getStatus()).isEqualTo(AiJobStatus.failed);
        assertThat(job.getAttempts()).isEqualTo(2);
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getNextRunAt()).isNull();
    }

    @Test
    void markMethodsSkipCanceledJobsAndPrivateTimingHelpersStayBounded() throws Exception {
        AiJobWorker worker = new AiJobWorker(jdbcTemplate, jobRepository, jobProcessor, usageLedgerService, costEstimator, cancellationRegistry, "worker-1", 30, 3, 1000, 8000, 1);
        AiJobEntity canceled = queuedJob(UUID.randomUUID());
        canceled.setStatus(AiJobStatus.canceled);
        when(jdbcTemplate.query(eq("select status from app_ai.ai_jobs where job_id = ?"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq(canceled.getJobId())))
                .thenReturn("canceled");

        worker.markCompleted(canceled, null);
        worker.markFailed(canceled, new IllegalStateException("boom"));

        verify(jobRepository, never()).save(any());
        verify(usageLedgerService, never()).recordUsage(any(), any(), any(), any(), any(), any(), any(), any(), any());

        Method heartbeatMethod = AiJobWorker.class.getDeclaredMethod("resolveHeartbeatIntervalMs");
        heartbeatMethod.setAccessible(true);
        long heartbeat = (long) heartbeatMethod.invoke(worker);
        assertThat(heartbeat).isEqualTo(10_000L);

        Method backoffMethod = AiJobWorker.class.getDeclaredMethod("computeBackoff", int.class);
        backoffMethod.setAccessible(true);
        assertThat((long) backoffMethod.invoke(worker, 1)).isEqualTo(1000L);
        assertThat((long) backoffMethod.invoke(worker, 4)).isEqualTo(8000L);
    }

    private AiJobEntity queuedJob(UUID jobId) {
        AiJobEntity job = new AiJobEntity();
        job.setJobId(jobId);
        job.setRequestId(UUID.randomUUID());
        job.setUserId(UUID.randomUUID());
        job.setType(AiJobType.generic);
        job.setStatus(AiJobStatus.queued);
        job.setProgress(0);
        job.setAttempts(0);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        return job;
    }
}
