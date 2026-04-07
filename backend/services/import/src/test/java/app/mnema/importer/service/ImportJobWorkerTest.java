package app.mnema.importer.service;

import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportJobType;
import app.mnema.importer.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportJobWorkerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ImportJobRepository jobRepository;

    @Mock
    private ImportProcessor importProcessor;

    @Mock
    private ExportProcessor exportProcessor;

    @Test
    void pollProcessesExportJobsAndMarksThemCompleted() {
        ImportJobEntity job = job(ImportJobType.export_job);
        ImportJobWorker worker = org.mockito.Mockito.spy(new ImportJobWorker(
                jdbcTemplate, jobRepository, importProcessor, exportProcessor, "worker-1", 300
        ));
        doReturn(Optional.of(job)).when(worker).claimNextJob();
        when(jobRepository.findById(job.getJobId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        worker.poll();

        verify(exportProcessor).process(job);
        verify(importProcessor, never()).process(any());
        ArgumentCaptor<ImportJobEntity> saved = ArgumentCaptor.forClass(ImportJobEntity.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ImportJobStatus.completed);
        assertThat(saved.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    void pollMarksFailedImportJobsWhenProcessorThrows() {
        ImportJobEntity job = job(ImportJobType.import_job);
        ImportJobWorker worker = org.mockito.Mockito.spy(new ImportJobWorker(
                jdbcTemplate, jobRepository, importProcessor, exportProcessor, "worker-1", 300
        ));
        doReturn(Optional.of(job)).when(worker).claimNextJob();
        doThrow(new IllegalArgumentException("bad import payload")).when(importProcessor).process(job);
        when(jobRepository.findById(job.getJobId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        worker.poll();

        ArgumentCaptor<ImportJobEntity> saved = ArgumentCaptor.forClass(ImportJobEntity.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ImportJobStatus.failed);
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("bad import payload");
    }

    @Test
    void claimNextJobReturnsEmptyWhenNothingIsClaimed() {
        ImportJobWorker worker = new ImportJobWorker(jdbcTemplate, jobRepository, importProcessor, exportProcessor, "worker-1", 300);
        doReturn(null).when(jdbcTemplate).query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<UUID>>any(), anyLong(), eq("worker-1"));

        Optional<ImportJobEntity> job = worker.claimNextJob();

        assertThat(job).isEmpty();
    }

    @Test
    void claimNextJobLoadsClaimedEntityFromRepository() {
        UUID jobId = UUID.randomUUID();
        ImportJobEntity entity = job(ImportJobType.import_job);
        entity.setJobId(jobId);
        ImportJobWorker worker = new ImportJobWorker(jdbcTemplate, jobRepository, importProcessor, exportProcessor, "worker-1", 300);
        doReturn(jobId).when(jdbcTemplate).query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<UUID>>any(), anyLong(), eq("worker-1"));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        Optional<ImportJobEntity> job = worker.claimNextJob();

        assertThat(job).contains(entity);
    }

    private ImportJobEntity job(ImportJobType jobType) {
        ImportJobEntity job = new ImportJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setJobType(jobType);
        job.setStatus(ImportJobStatus.processing);
        job.setUserId(UUID.randomUUID());
        return job;
    }
}
