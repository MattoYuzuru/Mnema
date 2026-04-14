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
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    public AiJobExecutionService(AiJobStepRepository stepRepository,
                                 AiJobRepository jobRepository,
                                 TransactionTemplate transactionTemplate) {
        this.stepRepository = stepRepository;
        this.jobRepository = jobRepository;
        this.transactionTemplate = transactionTemplate;
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

    public void markProcessing(UUID jobId, String stepName) {
        updateStepInTransaction(jobId, stepName, AiJobStepStatus.processing, null);
    }

    public void markCompleted(UUID jobId, String stepName) {
        updateStepInTransaction(jobId, stepName, AiJobStepStatus.completed, null);
    }

    public void markFailed(UUID jobId, String stepName, String errorSummary) {
        updateStepInTransaction(jobId, stepName, AiJobStepStatus.failed, errorSummary);
    }

    public void updateStepProgress(UUID jobId, String stepName, double fraction) {
        if (jobId == null || stepName == null || stepName.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(ignored -> {
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
            double normalizedFraction = Math.max(0.0d, Math.min(fraction, 1.0d));
            int progress = calculateProgress(steps, stepName.trim(), normalizedFraction);
            job.setProgress(progress);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
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

    private void updateStepInTransaction(UUID jobId, String stepName, AiJobStepStatus status, String errorSummary) {
        transactionTemplate.executeWithoutResult(ignored -> updateStep(jobId, stepName, status, errorSummary));
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
        return calculateProgress(steps, null, null);
    }

    private int calculateProgress(List<AiJobStepEntity> steps, String hintedStepName, Double hintedFraction) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        int totalWeight = steps.stream()
                .mapToInt(step -> resolveStepWeight(step.getStepName()))
                .sum();
        if (totalWeight <= 0) {
            totalWeight = steps.size();
        }
        double value = 0.0d;
        boolean processing = false;
        for (AiJobStepEntity step : steps) {
            int weight = resolveStepWeight(step.getStepName());
            if (step.getStatus() == AiJobStepStatus.completed) {
                value += weight;
                continue;
            }
            if (step.getStatus() == AiJobStepStatus.processing) {
                processing = true;
                double fraction = 0.5d;
                if (hintedStepName != null
                        && hintedFraction != null
                        && hintedStepName.equals(step.getStepName())) {
                    fraction = Math.max(0.0d, Math.min(hintedFraction, 0.99d));
                }
                value += weight * fraction;
            }
        }
        int progress = (int) Math.floor((value / totalWeight) * 100.0d);
        if (processing && progress >= 100) {
            return 99;
        }
        return Math.max(0, Math.min(progress, 99));
    }

    private int resolveStepWeight(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            return 10;
        }
        return switch (stepName.trim()) {
            case "load_source" -> 10;
            case "prepare_context" -> 8;
            case "analyze_content" -> 12;
            case "generate_content" -> 36;
            case "generate_media" -> 14;
            case "generate_audio" -> 14;
            case "apply_changes" -> 6;
            default -> 10;
        };
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
