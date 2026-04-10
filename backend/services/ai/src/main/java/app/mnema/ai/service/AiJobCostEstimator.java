package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobCostResponse;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.provider.gemini.GeminiProps;
import app.mnema.ai.provider.grok.GrokProps;
import app.mnema.ai.provider.openai.OpenAiProps;
import app.mnema.ai.provider.qwen.QwenProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Service
public class AiJobCostEstimator {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final BigDecimal TEXT_TOKENS_PER_CHAR = new BigDecimal("0.25");
    private static final BigDecimal AUDIO_TOKENS_PER_CHAR = new BigDecimal("0.40");
    private static final BigDecimal AVERAGE_TTS_CHAR_FACTOR = new BigDecimal("0.65");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

    private final OpenAiProps openAiProps;
    private final GeminiProps geminiProps;
    private final QwenProps qwenProps;
    private final GrokProps grokProps;

    public AiJobCostEstimator(OpenAiProps openAiProps,
                              GeminiProps geminiProps,
                              QwenProps qwenProps,
                              GrokProps grokProps) {
        this.openAiProps = openAiProps;
        this.geminiProps = geminiProps;
        this.qwenProps = qwenProps;
        this.grokProps = grokProps;
    }

    public PlannedCost estimatePlanned(AiJobType type,
                                       JsonNode params,
                                       String provider,
                                       String model) {
        JsonNode safeParams = params == null ? NullNode.getInstance() : params;
        String resolvedProvider = normalize(provider);
        String resolvedModel = normalize(resolvePrimaryModel(resolvedProvider, model, safeParams));
        int estimatedInputTokens = estimateInputTokens(type, safeParams);
        int estimatedOutputTokens = estimateOutputTokens(type, safeParams);
        Pricing pricing = resolveTextPricing(resolvedProvider, resolvedModel);
        BigDecimal totalCost = estimateTextCost(estimatedInputTokens, estimatedOutputTokens, pricing)
                .add(estimateTtsCost(safeParams, resolvedProvider, resolveTtsModel(resolvedProvider, safeParams)))
                .add(estimateImageCost(safeParams, resolvedProvider, resolveImageModel(resolvedProvider, safeParams)))
                .add(estimateVideoCost(safeParams, resolvedProvider, resolveVideoModel(resolvedProvider, safeParams)));
        return new PlannedCost(
                estimatedInputTokens,
                estimatedOutputTokens,
                scale(totalCost),
                pricing.currency()
        );
    }

    public BigDecimal estimateRecordedCost(AiJobEntity job, AiJobProcessingResult result) {
        if (job == null || result == null) {
            return null;
        }
        if (result.costEstimate() != null && result.costEstimate().compareTo(BigDecimal.ZERO) > 0) {
            return scale(result.costEstimate());
        }
        String provider = normalize(result.provider());
        String model = normalize(result.model());
        Pricing pricing = resolveTextPricing(provider, normalize(resolvePrimaryModel(provider, model, job.getParamsJson())));
        BigDecimal total = estimateTextCost(result.tokensIn(), result.tokensOut(), pricing)
                .add(estimateTtsCost(job.getParamsJson(), provider, resolveTtsModel(provider, job.getParamsJson()), actualTtsCount(job), true))
                .add(estimateImageCost(job.getParamsJson(), provider, resolveImageModel(provider, job.getParamsJson()), actualImageCount(job)))
                .add(estimateVideoCost(job.getParamsJson(), provider, resolveVideoModel(provider, job.getParamsJson()), actualVideoCount(job)));
        return scale(total);
    }

    public AiJobCostResponse buildSnapshot(AiJobEntity job,
                                           String provider,
                                           String model,
                                           AiUsageLedgerEntity usage) {
        if (job == null) {
            return null;
        }
        PlannedCost planned = estimatePlanned(job.getType(), job.getParamsJson(), provider, model);
        String resolvedProvider = normalize(provider);
        String resolvedModel = normalize(resolvePrimaryModel(resolvedProvider, model, job.getParamsJson()));
        Pricing pricing = resolveTextPricing(resolvedProvider, resolvedModel);
        BigDecimal actualCost = usage == null
                ? null
                : scale(usage.getCostEstimate() != null
                ? usage.getCostEstimate()
                : estimateTextCost(usage.getTokensIn(), usage.getTokensOut(), pricing)
                        .add(estimateTtsCost(job.getParamsJson(), resolvedProvider, resolveTtsModel(resolvedProvider, job.getParamsJson()), actualTtsCount(job), true))
                        .add(estimateImageCost(job.getParamsJson(), resolvedProvider, resolveImageModel(resolvedProvider, job.getParamsJson()), actualImageCount(job)))
                        .add(estimateVideoCost(job.getParamsJson(), resolvedProvider, resolveVideoModel(resolvedProvider, job.getParamsJson()), actualVideoCount(job))));
        if (planned.isEmpty() && usage == null) {
            return null;
        }
        return new AiJobCostResponse(
                planned.inputTokens(),
                planned.outputTokens(),
                planned.cost(),
                planned.currency(),
                usage == null ? null : usage.getTokensIn(),
                usage == null ? null : usage.getTokensOut(),
                actualCost,
                usage == null ? null : pricing.currency()
        );
    }

    private int estimateInputTokens(AiJobType type, JsonNode params) {
        String mode = resolveMode(params);
        int promptTokens = stringTokens(params.path("input").asText(null))
                + stringTokens(params.path("instructions").asText(null));
        int targetCount = resolveTargetCount(type, params);
        int fieldCount = resolveFieldCount(params);
        return switch (mode) {
            case "generate_cards" -> 180 + promptTokens + targetCount * (40 + fieldCount * 18);
            case "missing_fields", "card_missing_fields" -> 160 + promptTokens + targetCount * Math.max(45, fieldCount * 28);
            case "missing_audio", "card_missing_audio" -> 80 + promptTokens + estimateTtsTextTokens(params, type);
            case "audit", "card_audit" -> 260 + promptTokens + targetCount * 90;
            case "import_preview" -> 220 + promptTokens + estimateSourceTokens(params);
            case "import_generate" -> 260 + promptTokens + estimateSourceTokens(params) + targetCount * (35 + fieldCount * 15);
            default -> Math.max(60, promptTokens + estimateSourceTokens(params) + targetCount * 40);
        };
    }

    private int estimateOutputTokens(AiJobType type, JsonNode params) {
        String mode = resolveMode(params);
        int targetCount = resolveTargetCount(type, params);
        int fieldCount = resolveFieldCount(params);
        return switch (mode) {
            case "generate_cards" -> targetCount * (55 + fieldCount * 24);
            case "missing_fields", "card_missing_fields" -> targetCount * Math.max(40, fieldCount * 22);
            case "missing_audio", "card_missing_audio" -> 30;
            case "audit", "card_audit" -> 220 + targetCount * 18;
            case "import_preview" -> 320;
            case "import_generate" -> targetCount * (50 + fieldCount * 20);
            default -> Math.max(40, targetCount * 50);
        };
    }

    private int estimateSourceTokens(JsonNode params) {
        if (hasText(params.path("sourceMediaId").asText(null))) {
            return 1200;
        }
        return stringTokens(params.path("sourceText").asText(null));
    }

    private int estimateTtsTextTokens(JsonNode params, AiJobType type) {
        int chars = estimateTtsChars(params, resolveTargetCount(type, params), false);
        return tokensFromChars(chars, TEXT_TOKENS_PER_CHAR);
    }

    private BigDecimal estimateTextCost(Integer inputTokens, Integer outputTokens, Pricing pricing) {
        if (pricing == null) {
            return ZERO;
        }
        BigDecimal input = perMillion(inputTokens, pricing.inputPerMillion());
        BigDecimal output = perMillion(outputTokens, pricing.outputPerMillion());
        return input.add(output);
    }

    private BigDecimal estimateTtsCost(JsonNode params,
                                       String provider,
                                       String ttsModel) {
        int count = plannedTtsCount(params);
        if (count <= 0) {
            return ZERO;
        }
        return estimateTtsCost(params, provider, ttsModel, count, false);
    }

    private BigDecimal estimateTtsCost(JsonNode params,
                                       String provider,
                                       String ttsModel,
                                       int count,
                                       boolean actual) {
        if (count <= 0) {
            return ZERO;
        }
        JsonNode safeParams = params == null ? NullNode.getInstance() : params;
        String resolvedProvider = normalize(provider);
        String resolvedModel = normalize(ttsModel);
        int estimatedChars = estimateTtsChars(safeParams, count, actual);
        if (estimatedChars <= 0) {
            return ZERO;
        }
        if ("qwen".equals(resolvedProvider)) {
            return perTenThousandChars(estimatedChars, new BigDecimal("0.733924"));
        }
        if ("grok".equals(resolvedProvider)) {
            return perMillionChars(estimatedChars, new BigDecimal("4.20"));
        }
        if ("gemini".equals(resolvedProvider) && resolvedModel.contains("tts")) {
            BigDecimal input = perMillion(tokensFromChars(estimatedChars, TEXT_TOKENS_PER_CHAR), new BigDecimal("0.50"));
            BigDecimal output = perMillion(tokensFromChars(estimatedChars, AUDIO_TOKENS_PER_CHAR), new BigDecimal("10.00"));
            return input.add(output);
        }
        if ("openai".equals(resolvedProvider) && resolvedModel.contains("tts")) {
            BigDecimal input = perMillion(tokensFromChars(estimatedChars, TEXT_TOKENS_PER_CHAR), new BigDecimal("0.60"));
            BigDecimal output = perMillion(tokensFromChars(estimatedChars, AUDIO_TOKENS_PER_CHAR), new BigDecimal("12.00"));
            return input.add(output);
        }
        return ZERO;
    }

    private BigDecimal estimateImageCost(JsonNode params, String provider, String imageModel) {
        return estimateImageCost(params, provider, imageModel, plannedImageCount(params));
    }

    private BigDecimal estimateImageCost(JsonNode params, String provider, String imageModel, int imageCount) {
        if (imageCount <= 0) {
            return ZERO;
        }
        String resolvedProvider = normalize(provider);
        String resolvedModel = normalize(imageModel);
        if ("gemini".equals(resolvedProvider) && resolvedModel.contains("flash-image")) {
            return new BigDecimal("0.039").multiply(BigDecimal.valueOf(imageCount));
        }
        if ("qwen".equals(resolvedProvider) && resolvedModel.contains("qwen-image-plus")) {
            return new BigDecimal("0.20").multiply(BigDecimal.valueOf(imageCount));
        }
        return ZERO;
    }

    private BigDecimal estimateVideoCost(JsonNode params, String provider, String videoModel) {
        return estimateVideoCost(params, provider, videoModel, plannedVideoCount(params));
    }

    private BigDecimal estimateVideoCost(JsonNode params, String provider, String videoModel, int videoCount) {
        if (videoCount <= 0) {
            return ZERO;
        }
        String resolvedProvider = normalize(provider);
        String resolvedModel = normalize(videoModel);
        int durationSeconds = positiveInt(params.path("video").path("durationSeconds"), 8);
        if ("qwen".equals(resolvedProvider) && resolvedModel.contains("wan2.2-t2v-plus")) {
            return new BigDecimal("0.733924")
                    .multiply(BigDecimal.valueOf(durationSeconds))
                    .multiply(BigDecimal.valueOf(videoCount));
        }
        return ZERO;
    }

    private Pricing resolveTextPricing(String provider, String model) {
        String resolvedProvider = normalize(provider);
        String resolvedModel = normalize(model);
        return switch (resolvedProvider) {
            case "openai" -> {
                if (resolvedModel.contains("gpt-4.1-mini")) {
                    yield new Pricing("USD", new BigDecimal("0.40"), new BigDecimal("1.60"));
                }
                if (resolvedModel.contains("gpt-4o-mini")) {
                    yield new Pricing("USD", new BigDecimal("0.15"), new BigDecimal("0.60"));
                }
                yield new Pricing("USD", new BigDecimal("0.40"), new BigDecimal("1.60"));
            }
            case "gemini" -> {
                if (resolvedModel.contains("gemini-2.5-flash")) {
                    yield new Pricing("USD", new BigDecimal("0.30"), new BigDecimal("2.50"));
                }
                if (resolvedModel.contains("gemini-2.0-flash")) {
                    yield new Pricing("USD", new BigDecimal("0.10"), new BigDecimal("0.40"));
                }
                yield new Pricing("USD", new BigDecimal("0.10"), new BigDecimal("0.40"));
            }
            case "anthropic", "claude" -> new Pricing("USD", new BigDecimal("3.00"), new BigDecimal("15.00"));
            case "qwen" -> {
                if (resolvedModel.contains("qwen2.5-3b")) {
                    yield new Pricing("CNY", new BigDecimal("0.30"), new BigDecimal("0.90"));
                }
                if (resolvedModel.contains("qwen2.5-7b")) {
                    yield new Pricing("CNY", new BigDecimal("0.50"), new BigDecimal("1.00"));
                }
                yield new Pricing("CNY", new BigDecimal("0.30"), new BigDecimal("0.90"));
            }
            case "grok" -> new Pricing("USD", new BigDecimal("0.20"), new BigDecimal("0.50"));
            default -> new Pricing("USD", BigDecimal.ZERO, BigDecimal.ZERO);
        };
    }

    private String resolvePrimaryModel(String provider, String model, JsonNode params) {
        if (hasText(model)) {
            return model;
        }
        JsonNode safeParams = params == null ? NullNode.getInstance() : params;
        String fromParams = safeParams.path("model").asText(null);
        if (hasText(fromParams)) {
            return fromParams;
        }
        return switch (normalize(provider)) {
            case "openai" -> openAiProps.defaultModel();
            case "gemini" -> geminiProps.defaultModel();
            case "qwen" -> qwenProps.defaultModel();
            case "grok" -> grokProps.defaultModel();
            default -> null;
        };
    }

    private String resolveTtsModel(String provider, JsonNode params) {
        JsonNode safeParams = params == null ? NullNode.getInstance() : params;
        String explicit = safeParams.path("tts").path("model").asText(null);
        if (hasText(explicit)) {
            return explicit;
        }
        return switch (normalize(provider)) {
            case "openai" -> openAiProps.defaultTtsModel();
            case "gemini" -> geminiProps.defaultTtsModel();
            case "qwen" -> qwenProps.defaultTtsModel();
            case "grok" -> grokProps.defaultTtsModel();
            default -> null;
        };
    }

    private String resolveImageModel(String provider, JsonNode params) {
        JsonNode safeParams = params == null ? NullNode.getInstance() : params;
        String explicit = safeParams.path("image").path("model").asText(null);
        if (hasText(explicit)) {
            return explicit;
        }
        return switch (normalize(provider)) {
            case "gemini" -> null;
            case "qwen" -> qwenProps.defaultImageModel();
            case "grok" -> grokProps.defaultImageModel();
            default -> null;
        };
    }

    private String resolveVideoModel(String provider, JsonNode params) {
        JsonNode safeParams = params == null ? NullNode.getInstance() : params;
        String explicit = safeParams.path("video").path("model").asText(null);
        if (hasText(explicit)) {
            return explicit;
        }
        return switch (normalize(provider)) {
            case "qwen" -> qwenProps.defaultVideoModel();
            case "grok" -> grokProps.defaultVideoModel();
            default -> null;
        };
    }

    private int plannedTtsCount(JsonNode params) {
        String mode = resolveMode(params);
        if ("missing_audio".equals(mode) || "card_missing_audio".equals(mode)) {
            return resolveTargetCount(null, params);
        }
        if (!params.path("tts").path("enabled").asBoolean(false)) {
            return 0;
        }
        return resolveTargetCount(null, params) * Math.max(1, resolveTtsMappingCount(params));
    }

    private int actualTtsCount(AiJobEntity job) {
        JsonNode summary = job.getResultSummary();
        return summary == null ? 0 : positiveInt(summary.path("ttsGenerated"), 0);
    }

    private int actualImageCount(AiJobEntity job) {
        JsonNode summary = job.getResultSummary();
        return summary == null ? 0 : positiveInt(summary.path("imagesGenerated"), 0);
    }

    private int actualVideoCount(AiJobEntity job) {
        JsonNode summary = job.getResultSummary();
        return summary == null ? 0 : positiveInt(summary.path("videosGenerated"), 0);
    }

    private int plannedImageCount(JsonNode params) {
        if (!params.path("image").path("enabled").asBoolean(false)) {
            return 0;
        }
        return resolveTargetCount(null, params);
    }

    private int plannedVideoCount(JsonNode params) {
        if (!params.path("video").path("enabled").asBoolean(false)) {
            return 0;
        }
        return resolveTargetCount(null, params);
    }

    private int estimateTtsChars(JsonNode params, int generatedCount, boolean actual) {
        if (generatedCount <= 0) {
            return 0;
        }
        int maxChars = positiveInt(params.path("tts").path("maxChars"), 300);
        BigDecimal factor = actual ? BigDecimal.ONE : AVERAGE_TTS_CHAR_FACTOR;
        BigDecimal estimatedPerAudio = BigDecimal.valueOf(maxChars).multiply(factor);
        return estimatedPerAudio.multiply(BigDecimal.valueOf(generatedCount)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private int resolveTargetCount(AiJobType type, JsonNode params) {
        JsonNode cardIds = params.path("cardIds");
        if (cardIds.isArray() && !cardIds.isEmpty()) {
            return Math.max(1, cardIds.size());
        }
        int count = positiveInt(params.path("count"), 0);
        int countLimit = positiveInt(params.path("countLimit"), 0);
        if (count > 0 && countLimit > 0) {
            return Math.max(1, Math.min(count, countLimit));
        }
        if (count > 0) {
            return Math.max(1, count);
        }
        int limit = positiveInt(params.path("limit"), 0);
        if (limit > 0) {
            return Math.max(1, limit);
        }
        int fieldLimit = maxFieldLimit(params.path("fieldLimits"));
        if (fieldLimit > 0) {
            return Math.max(1, fieldLimit);
        }
        String mode = resolveMode(params);
        if (mode.startsWith("card_") || type == AiJobType.tts) {
            return 1;
        }
        return "generate_cards".equals(mode) ? 10 : 3;
    }

    private int resolveFieldCount(JsonNode params) {
        if (params.path("fields").isArray() && !params.path("fields").isEmpty()) {
            return params.path("fields").size();
        }
        if (params.path("fieldLimits").isArray() && !params.path("fieldLimits").isEmpty()) {
            return params.path("fieldLimits").size();
        }
        return 1;
    }

    private int resolveTtsMappingCount(JsonNode params) {
        JsonNode mappings = params.path("tts").path("mappings");
        if (mappings.isArray() && !mappings.isEmpty()) {
            return mappings.size();
        }
        JsonNode fields = params.path("tts").path("fields");
        if (fields.isArray() && !fields.isEmpty()) {
            return fields.size();
        }
        return 1;
    }

    private String resolveMode(JsonNode params) {
        String mode = params.path("mode").asText(null);
        return hasText(mode) ? normalize(mode) : "generic";
    }

    private int maxFieldLimit(JsonNode fieldLimitsNode) {
        if (!fieldLimitsNode.isArray()) {
            return 0;
        }
        int max = 0;
        for (JsonNode node : fieldLimitsNode) {
            max = Math.max(max, positiveInt(node.path("limit"), 0));
        }
        return max;
    }

    private int stringTokens(String text) {
        if (!hasText(text)) {
            return 0;
        }
        return (int) Math.ceil(text.trim().length() / 4.0d);
    }

    private int tokensFromChars(int chars, BigDecimal factor) {
        if (chars <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(chars)
                .multiply(factor)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private BigDecimal perMillion(Integer units, BigDecimal price) {
        if (units == null || units <= 0 || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return ZERO;
        }
        return BigDecimal.valueOf(units)
                .multiply(price)
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal perTenThousandChars(int chars, BigDecimal price) {
        if (chars <= 0 || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return ZERO;
        }
        return BigDecimal.valueOf(chars)
                .multiply(price)
                .divide(TEN_THOUSAND, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal perMillionChars(int chars, BigDecimal price) {
        if (chars <= 0 || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return ZERO;
        }
        return BigDecimal.valueOf(chars)
                .multiply(price)
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    public record PlannedCost(
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal cost,
            String currency
    ) {
        boolean isEmpty() {
            return (inputTokens == null || inputTokens <= 0)
                    && (outputTokens == null || outputTokens <= 0)
                    && (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0);
        }
    }

    private record Pricing(String currency, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }
}
