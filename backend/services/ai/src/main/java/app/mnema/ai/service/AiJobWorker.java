package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.repository.AiJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class AiJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AiJobWorker.class);

    private final JdbcTemplate jdbcTemplate;
    private final AiJobRepository jobRepository;
    private final AiJobProcessor jobProcessor;
    private final AiUsageLedgerService usageLedgerService;
    private final String workerId;
    private final Duration lockTtl;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final int concurrentJobs;
    private final Semaphore jobSlots;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeatScheduler;

    public AiJobWorker(JdbcTemplate jdbcTemplate,
                       AiJobRepository jobRepository,
                       AiJobProcessor jobProcessor,
                       AiUsageLedgerService usageLedgerService,
                       @Value("${app.ai.jobs.worker-id:}") String workerId,
                       @Value("${app.ai.jobs.lock-ttl-seconds:300}") long lockTtlSeconds,
                       @Value("${app.ai.jobs.max-attempts:3}") int maxAttempts,
                       @Value("${app.ai.jobs.backoff-ms:2000}") long baseBackoffMs,
                       @Value("${app.ai.jobs.max-backoff-ms:30000}") long maxBackoffMs,
                       @Value("${app.ai.jobs.concurrent-jobs:2}") int concurrentJobs) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobRepository = jobRepository;
        this.jobProcessor = jobProcessor;
        this.usageLedgerService = usageLedgerService;
        this.workerId = (workerId == null || workerId.isBlank()) ? defaultWorkerId() : workerId;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.baseBackoffMs = Math.max(baseBackoffMs, 0);
        this.maxBackoffMs = Math.max(maxBackoffMs, this.baseBackoffMs);
        this.concurrentJobs = Math.max(concurrentJobs, 1);
        this.jobSlots = new Semaphore(this.concurrentJobs);
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ai-job-worker-", 0).factory());
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("ai-job-heartbeat-", 0).factory()
        );
    }

    @Scheduled(fixedDelayString = "${app.ai.jobs.poll-interval-ms:2000}")
    public void poll() {
        while (jobSlots.tryAcquire()) {
            Optional<AiJobEntity> jobOpt = claimNextJob();
            if (jobOpt.isEmpty()) {
                jobSlots.release();
                return;
            }
            submitJob(jobOpt.get());
        }
    }

    private void handleJob(AiJobEntity job) {
        if (isCanceled(job.getJobId())) {
            return;
        }
        ScheduledFuture<?> heartbeat = scheduleLockHeartbeat(job.getJobId());
        try {
            AiJobProcessingResult result = jobProcessor.process(job);
            markCompleted(job, result);
        } catch (Exception ex) {
            log.warn("AI job failed jobId={} errorType={} message={}", job.getJobId(), ex.getClass().getSimpleName(), safeMessage(ex));
            markFailed(job, ex);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> scheduleLockHeartbeat(UUID jobId) {
        long intervalMs = resolveHeartbeatIntervalMs();
        return heartbeatScheduler.scheduleAtFixedRate(
                () -> touchLock(jobId),
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    private long resolveHeartbeatIntervalMs() {
        long ttlMs = Math.max(lockTtl.toMillis(), 1_000L);
        long intervalMs = ttlMs / 3L;
        if (intervalMs < 5_000L) {
            return 5_000L;
        }
        if (intervalMs > 30_000L) {
            return 30_000L;
        }
        return intervalMs;
    }

    private void touchLock(UUID jobId) {
        try {
            jdbcTemplate.update(
                    """
                    update app_ai.ai_jobs
                    set locked_at = now(),
                        updated_at = now()
                    where job_id = ?
                      and status = 'processing'
                      and locked_by = ?
                    """,
                    jobId,
                    workerId
            );
        } catch (Exception ex) {
            log.warn("AI job heartbeat failed jobId={} workerId={} error={}", jobId, workerId, safeMessage(ex));
        }
    }

    private void submitJob(AiJobEntity job) {
        try {
            executor.execute(() -> {
                try {
                    handleJob(job);
                } finally {
                    jobSlots.release();
                }
            });
        } catch (RejectedExecutionException ex) {
            jobSlots.release();
            log.warn("AI job executor rejected jobId={}", job.getJobId());
            markFailed(job, ex);
        }
    }

    @Transactional
    public Optional<AiJobEntity> claimNextJob() {
        UUID jobId = jdbcTemplate.query(
                """
                with next_job as (
                    select job_id
                    from app_ai.ai_jobs
                    where (status = 'queued'
                           or (status = 'processing' and locked_at < now() - (? * interval '1 second')))
                      and (next_run_at is null or next_run_at <= now())
                    order by created_at asc
                    limit 1
                    for update skip locked
                )
                update app_ai.ai_jobs
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
    public void markCompleted(AiJobEntity job, AiJobProcessingResult result) {
        if (isCanceled(job.getJobId())) {
            return;
        }
        Instant now = Instant.now();
        job.setStatus(AiJobStatus.completed);
        job.setProgress(100);
        job.setResultSummary(result == null ? null : result.resultSummary());
        job.setCompletedAt(now);
        job.setUpdatedAt(now);
        job.setLockedAt(null);
        job.setLockedBy(null);
        job.setNextRunAt(null);
        job.setErrorMessage(null);
        if (result != null) {
            usageLedgerService.recordUsage(
                    job.getRequestId(),
                    job.getJobId(),
                    job.getUserId(),
                    result.tokensIn(),
                    result.tokensOut(),
                    result.costEstimate(),
                    result.provider(),
                    result.model(),
                    resolvePromptHash(job, result)
            );
        }
        jobRepository.save(job);
    }

    @Transactional
    public void markFailed(AiJobEntity job, Exception ex) {
        if (isCanceled(job.getJobId())) {
            return;
        }
        Instant now = Instant.now();
        int attempts = job.getAttempts() == null ? 1 : job.getAttempts() + 1;
        job.setAttempts(attempts);
        job.setLockedAt(null);
        job.setLockedBy(null);
        job.setUpdatedAt(now);
        String errorSummary = ex == null ? "Job failed" : ex.getClass().getSimpleName();
        job.setErrorMessage(errorSummary);

        if (attempts < maxAttempts) {
            job.setStatus(AiJobStatus.queued);
            job.setNextRunAt(now.plusMillis(computeBackoff(attempts)));
        } else {
            job.setStatus(AiJobStatus.failed);
            job.setCompletedAt(now);
            job.setNextRunAt(null);
        }
        jobRepository.save(job);
    }

    private long computeBackoff(int attempts) {
        long multiplier = 1L << Math.max(attempts - 1, 0);
        long backoff = baseBackoffMs * multiplier;
        if (backoff < 0) {
            return maxBackoffMs;
        }
        return Math.min(backoff, maxBackoffMs);
    }

    private String defaultWorkerId() {
        String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank()) ? "ai-worker" : host;
    }

    private String resolvePromptHash(AiJobEntity job, AiJobProcessingResult result) {
        if (result.promptHash() != null && !result.promptHash().isBlank()) {
            return result.promptHash();
        }
        return job.getInputHash();
    }

    private boolean isCanceled(UUID jobId) {
        String status = jdbcTemplate.query(
                "select status from app_ai.ai_jobs where job_id = ?",
                rs -> rs.next() ? rs.getString("status") : null,
                jobId
        );
        return "canceled".equalsIgnoreCase(status);
    }

    private String safeMessage(Exception ex) {
        if (ex == null) {
            return "";
        }
        String message = ex.getMessage();
        if (message == null) {
            return "";
        }
        String trimmed = message.replaceAll("[\\r\\n]+", " ").trim();
        int max = 200;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    @PreDestroy
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
        executor.shutdownNow();
    }
}
