package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobStepResponse;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobStepStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.provider.gemini.GeminiProps;
import app.mnema.ai.provider.grok.GrokProps;
import app.mnema.ai.provider.openai.OpenAiProps;
import app.mnema.ai.provider.qwen.QwenProps;
import app.mnema.ai.repository.AiJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AiJobEtaEstimator {

    private static final List<AiJobStatus> RUNNABLE_STATUSES = List.of(AiJobStatus.queued, AiJobStatus.processing);

    private final AiJobRepository jobRepository;
    private final OpenAiProps openAiProps;
    private final GeminiProps geminiProps;
    private final QwenProps qwenProps;
    private final GrokProps grokProps;
    private final int concurrentJobs;

    public AiJobEtaEstimator(AiJobRepository jobRepository,
                             OpenAiProps openAiProps,
                             GeminiProps geminiProps,
                             QwenProps qwenProps,
                             GrokProps grokProps,
                             @Value("${app.ai.jobs.concurrent-jobs:2}") int concurrentJobs) {
        this.jobRepository = jobRepository;
        this.openAiProps = openAiProps;
        this.geminiProps = geminiProps;
        this.qwenProps = qwenProps;
        this.grokProps = grokProps;
        this.concurrentJobs = Math.max(1, concurrentJobs);
    }

    public EtaEstimate estimate(AiJobEntity job,
                                AiJobExecutionService.ExecutionSnapshot snapshot,
                                String provider) {
        if (job == null || snapshot == null || isTerminal(job.getStatus())) {
            return EtaEstimate.unavailable();
        }
        Instant now = Instant.now();
        int ownRemainingSeconds = switch (job.getStatus()) {
            case processing -> estimateRemainingExecutionSeconds(job, snapshot, provider, now);
            case queued -> estimateTotalExecutionSeconds(job, snapshot, provider);
            default -> 0;
        };
        if (ownRemainingSeconds <= 0) {
            return EtaEstimate.unavailable();
        }

        Integer queueAhead = null;
        int remainingSeconds = ownRemainingSeconds;
        if (job.getStatus() == AiJobStatus.queued) {
            int jobsAhead = countRunnableJobsAhead(job, now);
            queueAhead = jobsAhead;
            remainingSeconds += estimateQueueWaitSeconds(jobsAhead, ownRemainingSeconds);
            remainingSeconds = Math.max(remainingSeconds, ownRemainingSeconds + secondsUntil(job.getNextRunAt(), now));
        }

        Instant estimatedCompletionAt = now.plusSeconds(Math.max(5, remainingSeconds));
        return new EtaEstimate(Math.max(5, remainingSeconds), estimatedCompletionAt, queueAhead);
    }

    private int estimateQueueWaitSeconds(int jobsAhead, int ownRemainingSeconds) {
        if (jobsAhead <= 0) {
            return 0;
        }
        int slotSeconds = clamp(ownRemainingSeconds, 20, 300);
        return Math.max(0, Math.floorDiv(jobsAhead, concurrentJobs) * slotSeconds);
    }

    private int estimateRemainingExecutionSeconds(AiJobEntity job,
                                                  AiJobExecutionService.ExecutionSnapshot snapshot,
                                                  String provider,
                                                  Instant now) {
        List<AiJobStepResponse> steps = snapshot.steps();
        if (steps == null || steps.isEmpty()) {
            return estimateTotalExecutionSeconds(job, snapshot, provider);
        }

        int remaining = 0;
        for (AiJobStepResponse step : steps) {
            int estimatedStepSeconds = estimateStepSeconds(step.stepName(), job, provider);
            if (step.status() == AiJobStepStatus.completed) {
                continue;
            }
            if (step.status() == AiJobStepStatus.processing) {
                long elapsed = step.startedAt() == null ? 0L : Math.max(0L, Duration.between(step.startedAt(), now).getSeconds());
                int floor = Math.max(3, estimatedStepSeconds / 5);
                remaining += Math.max(floor, estimatedStepSeconds - (int) elapsed);
                continue;
            }
            if (step.status() == AiJobStepStatus.failed) {
                remaining += Math.max(5, estimatedStepSeconds / 2);
                continue;
            }
            remaining += estimatedStepSeconds;
        }

        if (remaining <= 0) {
            return estimateFromProgress(job, snapshot, provider);
        }
        return remaining;
    }

    private int estimateTotalExecutionSeconds(AiJobEntity job,
                                              AiJobExecutionService.ExecutionSnapshot snapshot,
                                              String provider) {
        List<AiJobStepResponse> steps = snapshot.steps();
        if (steps == null || steps.isEmpty()) {
            String mode = resolveMode(job);
            return switch (mode) {
                case "generate_cards" -> 18 + resolveTargetCount(job) * 10;
                case "missing_fields", "missing_audio" -> 12 + resolveTargetCount(job) * 8;
                case "card_missing_fields", "card_missing_audio", "audit", "card_audit" -> 18;
                default -> 20;
            };
        }
        return steps.stream()
                .mapToInt(step -> estimateStepSeconds(step.stepName(), job, provider))
                .sum();
    }

    private int estimateFromProgress(AiJobEntity job,
                                     AiJobExecutionService.ExecutionSnapshot snapshot,
                                     String provider) {
        int total = estimateTotalExecutionSeconds(job, snapshot, provider);
        int progress = job.getProgress() == null ? 0 : clamp(job.getProgress(), 0, 99);
        int remaining = (int) Math.ceil(total * ((100 - progress) / 100.0d));
        return Math.max(5, remaining);
    }

    private int estimateStepSeconds(String stepName, AiJobEntity job, String provider) {
        String normalizedStep = normalize(stepName);
        String mode = resolveMode(job);
        JsonNode params = safeParams(job);
        int targetCount = resolveTargetCount(job);
        int fieldCount = resolveFieldCount(params);
        boolean ttsRequested = isTtsRequested(mode, params);
        boolean imageEnabled = isGenerationEnabled(params.path("image"));
        boolean videoEnabled = isGenerationEnabled(params.path("video"));
        int audioRequests = Math.max(1, targetCount * resolveAudioMappingCount(params));

        return switch (normalizedStep) {
            case "load_source" -> estimateLoadSourceSeconds(params);
            case "prepare_context" -> 4 + Math.min(targetCount, 20) * ("generate_cards".equals(mode) ? 2 : 1) + Math.max(0, fieldCount - 1);
            case "analyze_content" -> 8 + Math.min(targetCount, 20) * 2;
            case "generate_content" -> estimateGenerateContentSeconds(mode, targetCount, fieldCount);
            case "generate_media" -> estimateGenerateMediaSeconds(params, targetCount, imageEnabled, videoEnabled);
            case "generate_audio" -> estimateGenerateAudioSeconds(provider, audioRequests, ttsRequested);
            case "apply_changes" -> 3 + targetCount * 2;
            default -> 12;
        };
    }

    private int estimateLoadSourceSeconds(JsonNode params) {
        int seconds = 8;
        if (hasText(params.path("sourceMediaId").asText(null))) {
            seconds += 6;
        }
        String sourceType = normalize(params.path("sourceType").asText(null));
        return switch (sourceType) {
            case "audio", "mp3", "wav", "m4a" -> seconds + 18;
            case "video", "mp4", "mov" -> seconds + 20;
            case "pdf" -> seconds + 12;
            case "image", "png", "jpg", "jpeg" -> seconds + 8;
            default -> seconds;
        };
    }

    private int estimateGenerateContentSeconds(String mode, int targetCount, int fieldCount) {
        return switch (mode) {
            case "generate_cards" -> 12 + targetCount * 8;
            case "missing_fields", "card_missing_fields" -> 8 + targetCount * Math.max(5, fieldCount * 3);
            case "audit", "card_audit" -> 12 + Math.min(targetCount, 25) * 3;
            default -> 10 + targetCount * 6;
        };
    }

    private int estimateGenerateMediaSeconds(JsonNode params,
                                             int targetCount,
                                             boolean imageEnabled,
                                             boolean videoEnabled) {
        if (!imageEnabled && !videoEnabled) {
            return 3;
        }
        int seconds = 4;
        if (imageEnabled) {
            seconds += targetCount * 18;
        }
        if (videoEnabled) {
            int durationSeconds = positiveInt(params.path("video").path("durationSeconds"), 4);
            seconds += targetCount * (28 + Math.max(0, durationSeconds - 4) * 5);
        }
        return seconds;
    }

    private int estimateGenerateAudioSeconds(String provider, int audioRequests, boolean ttsRequested) {
        if (!ttsRequested) {
            return 3;
        }
        int rpm = Math.max(1, resolveProviderTtsRequestsPerMinute(provider));
        int rateLimitedSeconds = (int) Math.ceil((audioRequests * 60.0d) / rpm);
        int synthesisSeconds = 4 + audioRequests * 2;
        return Math.max(8, rateLimitedSeconds + synthesisSeconds);
    }

    private int resolveProviderTtsRequestsPerMinute(String provider) {
        return switch (normalize(provider)) {
            case "gemini" -> positiveInt(geminiProps.ttsRequestsPerMinute(), 10);
            case "qwen" -> positiveInt(qwenProps.ttsRequestsPerMinute(), 60);
            case "grok" -> positiveInt(grokProps.ttsRequestsPerMinute(), 60);
            case "openai" -> positiveInt(openAiProps.ttsRequestsPerMinute(), 60);
            default -> 45;
        };
    }

    private int resolveTargetCount(AiJobEntity job) {
        JsonNode params = safeParams(job);
        JsonNode cardIds = params.path("cardIds");
        if (cardIds.isArray() && !cardIds.isEmpty()) {
            return clamp(cardIds.size(), 1, 100);
        }
        JsonNode items = job.getResultSummary() == null ? null : job.getResultSummary().path("items");
        if (items != null && items.isArray() && !items.isEmpty()) {
            return clamp(items.size(), 1, 100);
        }
        int count = positiveInt(params.path("count"), 0);
        int countLimit = positiveInt(params.path("countLimit"), 0);
        if (count > 0 && countLimit > 0) {
            return clamp(Math.min(count, countLimit), 1, 100);
        }
        if (count > 0) {
            return clamp(count, 1, 100);
        }
        int limit = positiveInt(params.path("limit"), 0);
        if (limit > 0) {
            return clamp(limit, 1, 100);
        }
        int fieldLimit = maxFieldLimit(params.path("fieldLimits"));
        if (fieldLimit > 0) {
            return clamp(fieldLimit, 1, 100);
        }
        String mode = resolveMode(job);
        if (mode.startsWith("card_") || job.getType() == AiJobType.tts) {
            return 1;
        }
        return "generate_cards".equals(mode) ? 10 : 3;
    }

    private int resolveFieldCount(JsonNode params) {
        JsonNode fields = params.path("fields");
        if (fields.isArray() && !fields.isEmpty()) {
            return fields.size();
        }
        JsonNode fieldLimits = params.path("fieldLimits");
        if (fieldLimits.isArray() && !fieldLimits.isEmpty()) {
            return fieldLimits.size();
        }
        return 1;
    }

    private int resolveAudioMappingCount(JsonNode params) {
        JsonNode tts = params.path("tts");
        JsonNode mappings = tts.path("mappings");
        if (mappings.isArray() && !mappings.isEmpty()) {
            return clamp(mappings.size(), 1, 8);
        }
        JsonNode fields = tts.path("fields");
        if (fields.isArray() && !fields.isEmpty()) {
            return clamp(fields.size(), 1, 8);
        }
        return 1;
    }

    private boolean isTtsRequested(String mode, JsonNode params) {
        if ("missing_audio".equals(mode) || "card_missing_audio".equals(mode)) {
            return true;
        }
        JsonNode tts = params.path("tts");
        return isGenerationEnabled(tts) || (tts.path("mappings").isArray() && !tts.path("mappings").isEmpty());
    }

    private boolean isGenerationEnabled(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.has("enabled")) {
            return node.path("enabled").asBoolean(false);
        }
        return node.isObject() && node.size() > 0;
    }

    private String resolveMode(AiJobEntity job) {
        JsonNode params = safeParams(job);
        String mode = params.path("mode").asText(null);
        if (hasText(mode)) {
            return normalize(mode);
        }
        JsonNode summary = job.getResultSummary();
        if (summary != null && hasText(summary.path("mode").asText(null))) {
            return normalize(summary.path("mode").asText());
        }
        return "generic";
    }

    private JsonNode safeParams(AiJobEntity job) {
        JsonNode params = job.getParamsJson();
        return params == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : params;
    }

    private int maxFieldLimit(JsonNode fieldLimitsNode) {
        if (!fieldLimitsNode.isArray()) {
            return 0;
        }
        int max = 0;
        for (JsonNode fieldLimit : fieldLimitsNode) {
            max = Math.max(max, positiveInt(fieldLimit.path("limit"), 0));
        }
        return max;
    }

    private int countRunnableJobsAhead(AiJobEntity job, Instant now) {
        Instant createdAt = job.getCreatedAt();
        UUID jobId = job.getJobId();
        if (createdAt == null || jobId == null) {
            return 0;
        }
        long result = jobRepository.countRunnableJobsAhead(jobId, RUNNABLE_STATUSES, createdAt, now);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, result));
    }

    private boolean isTerminal(AiJobStatus status) {
        return status == AiJobStatus.completed
                || status == AiJobStatus.partial_success
                || status == AiJobStatus.failed
                || status == AiJobStatus.canceled;
    }

    private int secondsUntil(Instant target, Instant now) {
        if (target == null || now == null || !target.isAfter(now)) {
            return 0;
        }
        return (int) Duration.between(now, target).getSeconds();
    }

    private int positiveInt(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private int positiveInt(JsonNode node, int fallback) {
        if (node != null && node.canConvertToInt()) {
            int value = node.asInt();
            if (value > 0) {
                return value;
            }
        }
        return fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record EtaEstimate(
            Integer estimatedSecondsRemaining,
            Instant estimatedCompletionAt,
            Integer queueAhead
    ) {
        static EtaEstimate unavailable() {
            return new EtaEstimate(null, null, null);
        }
    }
}
