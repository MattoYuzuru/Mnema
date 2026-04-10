package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
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
                new OpenAiProps("https://api.openai.com", "", "gpt-4.1-mini", "gpt-4o-mini-tts", "alloy", "mp3", "", "", "1024x1024", "standard", "natural", "png", "", 8, "1280x720", 60, 5, 2000L, 30000L),
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
}
