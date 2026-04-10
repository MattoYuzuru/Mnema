package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobStepResponse;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiJobStepEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobStepStatus;
import app.mnema.ai.repository.AiJobRepository;
import app.mnema.ai.repository.AiJobStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AiJobExecutionService {

    private final AiJobStepRepository stepRepository;
    private final AiJobRepository jobRepository;

    public AiJobExecutionService(AiJobStepRepository stepRepository,
                                 AiJobRepository jobRepository) {
        this.stepRepository = stepRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void resetPlan(UUID jobId, List<String> stepNames) {
        if (jobId == null) {
            return;
        }
        stepRepository.deleteByJobId(jobId);
        Instant now = Instant.now();
        List<AiJobStepEntity> steps = new ArrayList<>();
        for (String stepName : sanitizeStepNames(stepNames)) {
            steps.add(new AiJobStepEntity(jobId, stepName, AiJobStepStatus.queued, null, null, null));
        }
        if (!steps.isEmpty()) {
            stepRepository.saveAll(steps);
        }
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProgress(steps.isEmpty() ? 0 : 1);
            job.setUpdatedAt(now);
            jobRepository.save(job);
        });
    }

    @Transactional
    public <T> T runStep(UUID jobId, String stepName, StepOperation<T> operation) {
        markProcessing(jobId, stepName);
        try {
            T result = operation.run();
            markCompleted(jobId, stepName);
            return result;
        } catch (Exception ex) {
            markFailed(jobId, stepName, summarizeError(ex));
            throw wrap(ex);
        }
    }

    @Transactional
    public void markProcessing(UUID jobId, String stepName) {
        updateStep(jobId, stepName, AiJobStepStatus.processing, null);
    }

    @Transactional
    public void markCompleted(UUID jobId, String stepName) {
        updateStep(jobId, stepName, AiJobStepStatus.completed, null);
    }

    @Transactional
    public void markFailed(UUID jobId, String stepName, String errorSummary) {
        updateStep(jobId, stepName, AiJobStepStatus.failed, errorSummary);
    }

    @Transactional(readOnly = true)
    public ExecutionSnapshot snapshot(UUID jobId) {
        List<AiJobStepEntity> steps = loadSteps(jobId);
        if (steps.isEmpty()) {
            return new ExecutionSnapshot(null, 0, 0, List.of());
        }
        int completed = 0;
        String currentStep = null;
        List<AiJobStepResponse> responses = new ArrayList<>(steps.size());
        for (AiJobStepEntity step : steps) {
            if (step.getStatus() == AiJobStepStatus.completed) {
                completed++;
            }
            if (currentStep == null && step.getStatus() == AiJobStepStatus.processing) {
                currentStep = step.getStepName();
            }
            responses.add(new AiJobStepResponse(
                    step.getStepName(),
                    step.getStatus(),
                    step.getStartedAt(),
                    step.getEndedAt(),
                    step.getErrorSummary()
            ));
        }
        return new ExecutionSnapshot(currentStep, completed, steps.size(), responses);
    }

    private void updateStep(UUID jobId, String stepName, AiJobStepStatus status, String errorSummary) {
        if (jobId == null || stepName == null || stepName.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        AiJobStepEntity step = stepRepository.findById(new app.mnema.ai.domain.composite.AiJobStepId(jobId, stepName))
                .orElseGet(() -> new AiJobStepEntity(jobId, stepName, AiJobStepStatus.queued, null, null, null));

        if (status == AiJobStepStatus.processing && step.getStatus() == AiJobStepStatus.completed) {
            refreshProgress(jobId, now);
            return;
        }

        if (status == AiJobStepStatus.processing && step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        if (status == AiJobStepStatus.completed || status == AiJobStepStatus.failed) {
            if (step.getStartedAt() == null) {
                step.setStartedAt(now);
            }
            step.setEndedAt(now);
        } else {
            step.setEndedAt(null);
        }
        step.setStatus(status);
        step.setErrorSummary(errorSummary);
        stepRepository.save(step);
        refreshProgress(jobId, now);
    }

    private void refreshProgress(UUID jobId, Instant now) {
        List<AiJobStepEntity> steps = loadSteps(jobId);
        Optional<AiJobEntity> jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return;
        }
        AiJobEntity job = jobOpt.get();
        if (job.getStatus() == AiJobStatus.completed
                || job.getStatus() == AiJobStatus.partial_success
                || job.getStatus() == AiJobStatus.failed
                || job.getStatus() == AiJobStatus.canceled) {
            return;
        }
        int progress = calculateProgress(steps);
        job.setProgress(progress);
        job.setUpdatedAt(now);
        jobRepository.save(job);
    }

    private int calculateProgress(List<AiJobStepEntity> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        int total = steps.size();
        long completed = steps.stream().filter(step -> step.getStatus() == AiJobStepStatus.completed).count();
        boolean processing = steps.stream().anyMatch(step -> step.getStatus() == AiJobStepStatus.processing);
        double value = completed + (processing ? 0.5d : 0d);
        int progress = (int) Math.floor((value / total) * 100.0d);
        if (processing && progress >= 100) {
            return 99;
        }
        return Math.max(0, Math.min(progress, 99));
    }

    private List<AiJobStepEntity> loadSteps(UUID jobId) {
        if (jobId == null) {
            return List.of();
        }
        return stepRepository.findByJobIdOrderByStartedAtAscStepNameAsc(jobId).stream()
                .sorted(Comparator
                        .comparing((AiJobStepEntity step) -> step.getStartedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AiJobStepEntity::getStepName))
                .toList();
    }

    private List<String> sanitizeStepNames(List<String> stepNames) {
        if (stepNames == null || stepNames.isEmpty()) {
            return List.of();
        }
        return stepNames.stream()
                .filter(step -> step != null && !step.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private RuntimeException wrap(Exception ex) {
        if (ex instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(ex.getMessage(), ex);
    }

    private String summarizeError(Exception ex) {
        if (ex == null || ex.getMessage() == null) {
            return null;
        }
        String message = ex.getMessage().replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 200 ? message.substring(0, 200) + "..." : message;
    }

    public record ExecutionSnapshot(
            String currentStep,
            int completedSteps,
            int totalSteps,
            List<AiJobStepResponse> steps
    ) {
    }

    @FunctionalInterface
    public interface StepOperation<T> {
        T run() throws Exception;
    }
}
