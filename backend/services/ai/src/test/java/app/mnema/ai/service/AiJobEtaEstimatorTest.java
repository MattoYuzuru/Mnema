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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJobEtaEstimatorTest {

    @Mock
    private AiJobRepository jobRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiJobEtaEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new AiJobEtaEstimator(
                jobRepository,
                new OpenAiProps("https://api.openai.com", "", "gpt-4.1-mini", "gpt-4o-mini-tts", "alloy", "mp3", "", "", "1024x1024", "standard", "natural", "png", "", 8, "1280x720", 60, 12, 5, 2000L, 30000L, 10000L, 600000L),
                new GeminiProps("https://generativelanguage.googleapis.com", "gemini-2.0-flash", "gemini-2.5-flash-preview-tts", "Kore", "audio/wav", "gemini-2.0-flash", "gemini-2.5-flash-image", 10, 5, 2000L, 30000L),
                new QwenProps("https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com", "qwen2.5-3b-instruct", "qwen3-vl-flash", "qwen3-tts-flash", "Cherry", "wav", "qwen3-asr-flash", "qwen-image-plus", "1024x1024", null, null, "png", "wan2.2-t2v-plus", 10, "1280x720", 60, 5, 2000L, 30000L),
                new GrokProps("https://api.x.ai", "grok-4-fast-non-reasoning", "grok-voice-mini", "sage", "mp3", "", "grok-imagine-image", "1024x1024", null, null, "jpg", "grok-imagine-video", 8, "1280x720", 60, 5, 2000L, 30000L),
                2
        );
    }

    @Test
    void estimatePlannedIncludesQueueAndMediaStages() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "generate_cards");
        params.put("count", 4);
        params.putArray("fields").add("front").add("back");
        params.putObject("tts").put("enabled", true).put("maxChars", 180);
        params.putObject("image").put("enabled", true);
        when(jobRepository.countRunnableJobs(any(List.class), any(Instant.class))).thenReturn(3L);

        AiJobEtaEstimator.EtaEstimate eta = estimator.estimatePlanned(AiJobType.enrich, params, "openai");

        assertThat(eta).isNotNull();
        assertThat(eta.queueAhead()).isEqualTo(3);
        assertThat(eta.estimatedSecondsRemaining()).isNotNull().isPositive();
        assertThat(eta.estimatedCompletionAt()).isNotNull().isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void estimateReturnsUnavailableForTerminalJobs() {
        AiJobEntity job = new AiJobEntity();
        job.setStatus(AiJobStatus.completed);
        job.setType(AiJobType.generic);
        job.setParamsJson(objectMapper.createObjectNode().put("mode", "generate_cards"));

        AiJobEtaEstimator.EtaEstimate eta = estimator.estimate(job, new AiJobExecutionService.ExecutionSnapshot(null, 0, 0, List.of()), "openai");

        assertThat(eta.estimatedSecondsRemaining()).isNull();
        assertThat(eta.estimatedCompletionAt()).isNull();
        assertThat(eta.queueAhead()).isNull();
    }

    @Test
    void estimateUsesRunnableJobsAheadForQueuedJobs() {
        UUID jobId = UUID.randomUUID();
        AiJobEntity job = new AiJobEntity();
        job.setJobId(jobId);
        job.setStatus(AiJobStatus.queued);
        job.setType(AiJobType.generic);
        job.setCreatedAt(Instant.now().minusSeconds(10));
        job.setParamsJson(objectMapper.createObjectNode()
                .put("mode", "import_generate")
                .put("count", 2)
                .put("sourceMediaId", UUID.randomUUID().toString())
                .putObject("video").put("enabled", true).put("durationSeconds", 6));
        when(jobRepository.countRunnableJobsAhead(eq(jobId), any(List.class), any(Instant.class), any(Instant.class))).thenReturn(2L);

        AiJobEtaEstimator.EtaEstimate eta = estimator.estimate(job, new AiJobExecutionService.ExecutionSnapshot(null, 0, 0, List.of()), "qwen");

        assertThat(eta.queueAhead()).isEqualTo(2);
        assertThat(eta.estimatedSecondsRemaining()).isNotNull().isPositive();
    }

    @Test
    void estimatePlannedUsesLocalOllamaAudioThroughput() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "missing_audio");
        params.putArray("cardIds")
                .add(UUID.randomUUID().toString())
                .add(UUID.randomUUID().toString())
                .add(UUID.randomUUID().toString());
        params.putObject("tts").put("enabled", true).put("model", "kokoro-tts");
        when(jobRepository.countRunnableJobs(any(List.class), any(Instant.class))).thenReturn(0L);

        AiJobEtaEstimator.EtaEstimate localEta = estimator.estimatePlanned(AiJobType.tts, params, "ollama");
        AiJobEtaEstimator.EtaEstimate openAiEta = estimator.estimatePlanned(AiJobType.tts, params, "openai");

        assertThat(localEta.estimatedSecondsRemaining()).isNotNull().isGreaterThan(openAiEta.estimatedSecondsRemaining());
    }

    @Test
    void estimatePlannedUsesSlowerLocalImportContentThroughput() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "import_generate");
        params.put("count", 40);
        params.put("sourceMediaId", UUID.randomUUID().toString());
        params.putArray("fields")
                .add("term")
                .add("translation")
                .add("example")
                .add("note")
                .add("audio");
        when(jobRepository.countRunnableJobs(any(List.class), any(Instant.class))).thenReturn(0L);

        AiJobEtaEstimator.EtaEstimate localEta = estimator.estimatePlanned(AiJobType.generic, params, "ollama");
        AiJobEtaEstimator.EtaEstimate openAiEta = estimator.estimatePlanned(AiJobType.generic, params, "openai");

        assertThat(localEta.estimatedSecondsRemaining()).isNotNull().isGreaterThan(openAiEta.estimatedSecondsRemaining());
    }

    @Test
    void estimatePlannedAccountsForOcrSourceNormalization() {
        ObjectNode cleanParams = objectMapper.createObjectNode();
        cleanParams.put("mode", "import_generate");
        cleanParams.put("count", 12);
        cleanParams.put("sourceMediaId", UUID.randomUUID().toString());
        cleanParams.put("sourceExtraction", "pdf");
        cleanParams.putArray("fields").add("term").add("translation").add("example");

        ObjectNode noisyParams = cleanParams.deepCopy();
        noisyParams.put("sourceExtraction", "ocr");
        when(jobRepository.countRunnableJobs(any(List.class), any(Instant.class))).thenReturn(0L);

        AiJobEtaEstimator.EtaEstimate cleanEta = estimator.estimatePlanned(AiJobType.generic, cleanParams, "openai");
        AiJobEtaEstimator.EtaEstimate noisyEta = estimator.estimatePlanned(AiJobType.generic, noisyParams, "openai");

        assertThat(noisyEta.estimatedSecondsRemaining()).isNotNull().isGreaterThan(cleanEta.estimatedSecondsRemaining());
    }

    @Test
    void estimateProcessingTracksMixedStepStatuses() {
        AiJobEntity job = new AiJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setStatus(AiJobStatus.processing);
        job.setType(AiJobType.enrich);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "generate_cards");
        params.put("count", 3);
        params.putArray("fields").add("front").add("back");
        job.setParamsJson(params);

        Instant now = Instant.now();
        AiJobEtaEstimator.EtaEstimate eta = estimator.estimate(job, new AiJobExecutionService.ExecutionSnapshot(
                "generate_audio",
                1,
                4,
                List.of(
                        new AiJobStepResponse("prepare_context", AiJobStepStatus.completed, now.minusSeconds(60), now.minusSeconds(55), null),
                        new AiJobStepResponse("generate_content", AiJobStepStatus.processing, now.minusSeconds(20), null, null),
                        new AiJobStepResponse("generate_audio", AiJobStepStatus.failed, now.minusSeconds(10), now.minusSeconds(5), "timeout"),
                        new AiJobStepResponse("apply_changes", AiJobStepStatus.queued, null, null, null)
                )
        ), "ollama");

        assertThat(eta.estimatedSecondsRemaining()).isNotNull().isPositive();
        assertThat(eta.queueAhead()).isNull();
        assertThat(eta.estimatedCompletionAt()).isNotNull().isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void estimateFallsBackToProgressWhenProcessingStepsAreAlreadyCompleted() {
        AiJobEntity job = new AiJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setStatus(AiJobStatus.processing);
        job.setType(AiJobType.enrich);
        job.setProgress(45);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "missing_fields");
        params.putArray("cardIds")
                .add(UUID.randomUUID().toString())
                .add(UUID.randomUUID().toString());
        job.setParamsJson(params);

        Instant now = Instant.now();
        AiJobEtaEstimator.EtaEstimate eta = estimator.estimate(job, new AiJobExecutionService.ExecutionSnapshot(
                "apply_changes",
                2,
                2,
                List.of(
                        new AiJobStepResponse("prepare_context", AiJobStepStatus.completed, now.minusSeconds(30), now.minusSeconds(25), null),
                        new AiJobStepResponse("generate_content", AiJobStepStatus.completed, now.minusSeconds(24), now.minusSeconds(10), null)
                )
        ), "local-openai");

        assertThat(eta.estimatedSecondsRemaining()).isNotNull().isPositive();
        assertThat(eta.queueAhead()).isNull();
    }

    @Test
    void estimatePrefersProgressAwareEtaForLongRunningProcessingStep() {
        AiJobEntity job = new AiJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setStatus(AiJobStatus.processing);
        job.setType(AiJobType.enrich);
        job.setProgress(85);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "generate_cards");
        params.put("count", 12);
        params.putArray("fields").add("front").add("back");
        job.setParamsJson(params);

        Instant now = Instant.now();
        AiJobEtaEstimator.EtaEstimate eta = estimator.estimate(job, new AiJobExecutionService.ExecutionSnapshot(
                "generate_content",
                1,
                2,
                List.of(
                        new AiJobStepResponse("prepare_context", AiJobStepStatus.completed, now.minusSeconds(40), now.minusSeconds(35), null),
                        new AiJobStepResponse("generate_content", AiJobStepStatus.processing, now.minusSeconds(5), null, null)
                )
        ), "openai");

        assertThat(eta.estimatedSecondsRemaining()).isNotNull().isPositive().isLessThan(30);
        assertThat(eta.queueAhead()).isNull();
    }
}
