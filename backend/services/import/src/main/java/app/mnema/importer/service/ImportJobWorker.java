package app.mnema.importer.service;

import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportJobType;
import app.mnema.importer.repository.ImportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ImportJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportJobWorker.class);

    private final JdbcTemplate jdbcTemplate;
    private final ImportJobRepository jobRepository;
    private final ImportProcessor importProcessor;
    private final ExportProcessor exportProcessor;
    private final String workerId;
    private final Duration lockTtl;

    public ImportJobWorker(JdbcTemplate jdbcTemplate,
                           ImportJobRepository jobRepository,
                           ImportProcessor importProcessor,
                           ExportProcessor exportProcessor,
                           @Value("${app.import.worker-id:}") String workerId,
                           @Value("${app.import.lock-ttl-seconds:300}") long lockTtlSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobRepository = jobRepository;
        this.importProcessor = importProcessor;
        this.exportProcessor = exportProcessor;
        this.workerId = (workerId == null || workerId.isBlank()) ? defaultWorkerId() : workerId;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
    }

    @Scheduled(fixedDelayString = "${app.import.poll-interval-ms:2000}")
    public void poll() {
        Optional<ImportJobEntity> jobOpt = claimNextJob();
        jobOpt.ifPresent(this::handleJob);
    }

    private void handleJob(ImportJobEntity job) {
        try {
            if (job.getJobType() == ImportJobType.export_job) {
                exportProcessor.process(job);
            } else {
                importProcessor.process(job);
            }
            markCompleted(job.getJobId());
        } catch (Exception ex) {
            String error = summarizeError(ex);
            log.error(
                    "Import job failed: jobId={}, type={}, userId={}, targetDeckId={}, sourceType={}, error={}",
                    job.getJobId(),
                    job.getJobType(),
                    job.getUserId(),
                    job.getTargetDeckId(),
                    job.getSourceType(),
                    error,
                    ex
            );
            markFailed(job.getJobId(), error);
        }
    }

    @Transactional
    public Optional<ImportJobEntity> claimNextJob() {
        UUID jobId = jdbcTemplate.query(
                """
                with next_job as (
                    select job_id
                    from app_import.import_jobs
                    where status = 'queued'
                      and (locked_at is null or locked_at < now() - (? * interval '1 second'))
                    order by created_at asc
                    limit 1
                    for update skip locked
                )
                update app_import.import_jobs
                set status = 'processing',
                    locked_at = now(),
                    locked_by = ?,
                    started_at = coalesce(started_at, now()),
                    updated_at = now()
                where job_id in (select job_id from next_job)
                returning job_id
                """,
                rs -> rs.next() ? UUID.fromString(rs.getString("job_id")) : null,
                lockTtl.getSeconds(),
                workerId
        );

        if (jobId == null) {
            return Optional.empty();
        }
        return jobRepository.findById(jobId);
    }

    @Transactional
    public void markCompleted(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ImportJobStatus.completed);
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    public void markFailed(UUID jobId, String error) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ImportJobStatus.failed);
            job.setErrorMessage(error);
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    private String defaultWorkerId() {
        String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank()) ? "import-worker" : host;
    }

    private String summarizeError(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        String fallback = throwable.getMessage();
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return throwable.getClass().getSimpleName();
    }
}
