package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.provider.gemini.GeminiProps;
import app.mnema.ai.provider.grok.GrokProps;
import app.mnema.ai.provider.openai.OpenAiProps;
import app.mnema.ai.provider.qwen.QwenProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiJobCostEstimatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiJobCostEstimator estimator = new AiJobCostEstimator(
            new OpenAiProps("https://api.openai.com", "", "gpt-4.1-mini", "gpt-4o-mini-tts", "alloy", "mp3", "", "", "1024x1024", "standard", "natural", "png", "", 8, "1280x720", 60, 5, 2000L, 30000L),
            new GeminiProps("https://generativelanguage.googleapis.com", "gemini-2.0-flash", "gemini-2.5-flash-preview-tts", "Kore", "audio/wav", "gemini-2.0-flash", "gemini-2.5-flash-image", 10, 5, 2000L, 30000L),
            new QwenProps("https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com", "qwen2.5-3b-instruct", "qwen3-vl-flash", "qwen3-tts-flash", "Cherry", "wav", "qwen3-asr-flash", "qwen-image-plus", "1024x1024", null, null, "png", "wan2.2-t2v-plus", 10, "1280x720", 60, 5, 2000L, 30000L),
            new GrokProps("https://api.x.ai", "grok-4-fast-non-reasoning", "grok-voice-mini", "sage", "mp3", "", "grok-imagine-image", "1024x1024", null, null, "jpg", "grok-imagine-video", 8, "1280x720", 60, 5, 2000L, 30000L)
    );

    @Test
    void estimatePlannedReturnsPositiveTokenAndCostSnapshot() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "generate_cards");
        params.put("count", 5);
        params.put("input", "Generate travel vocabulary");
        params.putArray("fields").add("front").add("back");

        AiJobCostEstimator.PlannedCost planned = estimator.estimatePlanned(AiJobType.enrich, params, "openai", "gpt-4.1-mini");

        assertThat(planned.inputTokens()).isPositive();
        assertThat(planned.outputTokens()).isPositive();
        assertThat(planned.cost()).isPositive();
        assertThat(planned.currency()).isEqualTo("USD");
    }

    @Test
    void buildSnapshotCombinesEstimatedAndActualUsage() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "missing_audio");
        params.putArray("cardIds").add(UUID.randomUUID().toString()).add(UUID.randomUUID().toString());
        ObjectNode tts = params.putObject("tts");
        tts.put("enabled", true);
        tts.put("model", "qwen3-tts-flash");
        tts.put("maxChars", 240);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("ttsGenerated", 2);

        AiJobEntity job = new AiJobEntity();
        job.setType(AiJobType.tts);
        job.setParamsJson(params);
        job.setResultSummary(summary);

        AiUsageLedgerEntity usage = new AiUsageLedgerEntity();
        usage.setTokensIn(120);
        usage.setTokensOut(48);
        usage.setCostEstimate(new BigDecimal("0.041233"));

        var snapshot = estimator.buildSnapshot(job, "qwen", "qwen2.5-3b-instruct", usage);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.estimatedInputTokens()).isPositive();
        assertThat(snapshot.estimatedCost()).isPositive();
        assertThat(snapshot.estimatedCostCurrency()).isEqualTo("CNY");
        assertThat(snapshot.actualInputTokens()).isEqualTo(120);
        assertThat(snapshot.actualOutputTokens()).isEqualTo(48);
        assertThat(snapshot.actualCost()).isEqualByComparingTo("0.041233");
        assertThat(snapshot.actualCostCurrency()).isEqualTo("CNY");
    }

    @Test
    void estimateRecordedCostFallsBackToSummaryCountsWhenProcessorReturnsZeroCost() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "missing_audio");
        ObjectNode tts = params.putObject("tts");
        tts.put("enabled", true);
        tts.put("model", "grok-voice-mini");
        tts.put("maxChars", 300);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("ttsGenerated", 3);

        AiJobEntity job = new AiJobEntity();
        job.setParamsJson(params);
        job.setResultSummary(summary);

        AiJobProcessingResult result = new AiJobProcessingResult(
                summary,
                "grok",
                "grok-4-fast-non-reasoning",
                200,
                90,
                BigDecimal.ZERO,
                "hash"
        );

        BigDecimal cost = estimator.estimateRecordedCost(job, result);

        assertThat(cost).isNotNull();
        assertThat(cost).isPositive();
    }

    @Test
    void estimatePlannedIncludesGeminiTtsAndImageCosts() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "generate_cards");
        params.put("count", 2);
        params.put("provider", "gemini");
        params.put("model", "gemini-2.0-flash");
        params.putArray("fields").add("front").add("back");
        params.putObject("image").put("enabled", true).put("model", "gemini-2.5-flash-image");
        params.putObject("tts").put("enabled", true).put("model", "gemini-2.5-flash-preview-tts").put("maxChars", 200);

        AiJobCostEstimator.PlannedCost planned = estimator.estimatePlanned(AiJobType.enrich, params, "gemini", "gemini-2.0-flash");

        assertThat(planned.cost()).isPositive();
        assertThat(planned.currency()).isEqualTo("USD");
    }

    @Test
    void estimatePlannedIncludesQwenVideoCost() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "generate_cards");
        params.put("count", 1);
        params.put("provider", "qwen");
        params.put("model", "qwen2.5-3b-instruct");
        params.putObject("video").put("enabled", true).put("model", "wan2.2-t2v-plus").put("durationSeconds", 10);

        AiJobCostEstimator.PlannedCost planned = estimator.estimatePlanned(AiJobType.enrich, params, "qwen", "qwen2.5-3b-instruct");

        assertThat(planned.cost()).isPositive();
        assertThat(planned.currency()).isEqualTo("CNY");
    }

    @Test
    void estimateRecordedCostPrefersProvidedProcessorCost() {
        AiJobEntity job = new AiJobEntity();
        job.setParamsJson(objectMapper.createObjectNode().put("mode", "generate_cards"));
        job.setResultSummary(objectMapper.createObjectNode().put("imagesGenerated", 1));

        AiJobProcessingResult result = new AiJobProcessingResult(
                job.getResultSummary(),
                "openai",
                "gpt-4.1-mini",
                150,
                80,
                new BigDecimal("1.230000"),
                "hash"
        );

        BigDecimal cost = estimator.estimateRecordedCost(job, result);

        assertThat(cost).isEqualByComparingTo("1.230000");
    }

    @Test
    void buildSnapshotReturnsNullWhenNoEstimateOrUsageExists() {
        AiJobEntity job = new AiJobEntity();
        job.setType(AiJobType.generic);
        job.setParamsJson(objectMapper.createObjectNode());
        job.setResultSummary(objectMapper.createObjectNode());

        var snapshot = estimator.buildSnapshot(job, null, null, null);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.estimatedInputTokens()).isPositive();
        assertThat(snapshot.actualInputTokens()).isNull();
        assertThat(snapshot.actualCost()).isNull();
    }

    @Test
    void buildPlannedSnapshotReturnsEstimateForPreflight() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", "import_generate");
        params.put("count", 3);
        params.put("sourceMediaId", UUID.randomUUID().toString());
        params.putArray("fields").add("front").add("back");

        var snapshot = estimator.buildPlannedSnapshot(AiJobType.generic, params, "openai", "gpt-4.1-mini");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.estimatedInputTokens()).isPositive();
        assertThat(snapshot.estimatedOutputTokens()).isPositive();
        assertThat(snapshot.estimatedCost()).isPositive();
        assertThat(snapshot.actualInputTokens()).isNull();
        assertThat(snapshot.actualCost()).isNull();
    }

    @Test
    void buildPlannedSnapshotReturnsNullWhenNoPlannedEstimateExists() {
        var snapshot = estimator.buildPlannedSnapshot(AiJobType.generic, null, null, null);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.estimatedInputTokens()).isPositive();
        assertThat(snapshot.actualCost()).isNull();
    }
}
