package app.mnema.ai.provider.openai;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.client.core.CoreApiClient.CoreFieldTemplate;
import app.mnema.ai.client.core.CoreApiClient.CorePublicDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreTemplateResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardPage;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardResponse;
import app.mnema.ai.client.core.CoreApiClient.CreateCardRequestPayload;
import app.mnema.ai.client.core.CoreApiClient.UpdateUserCardRequest;
import app.mnema.ai.client.media.MediaApiClient;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AudioChunkingService;
import app.mnema.ai.service.AiImportContentService;
import app.mnema.ai.service.AiJobExecutionService;
import app.mnema.ai.service.AiJobProcessingResult;
import app.mnema.ai.service.AiProviderProcessor;
import app.mnema.ai.service.CardNoveltyService;
import app.mnema.ai.support.ImportItemExtractor;
import app.mnema.ai.provider.anki.AnkiTemplateSupport;
import app.mnema.ai.provider.audit.AuditAnalyzer;
import app.mnema.ai.vault.EncryptedSecret;
import app.mnema.ai.vault.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiJobProcessor implements AiProviderProcessor {

    private static final String PROVIDER = "openai";
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiJobProcessor.class);
    private static final String MODE_GENERATE_CARDS = "generate_cards";
    private static final String MODE_MISSING_FIELDS = "missing_fields";
    private static final String MODE_MISSING_AUDIO = "missing_audio";
    private static final String MODE_AUDIT = "audit";
    private static final String MODE_ENHANCE = "enhance_deck";
    private static final String MODE_CARD_AUDIT = "card_audit";
    private static final String MODE_CARD_MISSING_FIELDS = "card_missing_fields";
    private static final String MODE_CARD_MISSING_AUDIO = "card_missing_audio";
    private static final String MODE_IMPORT_PREVIEW = "import_preview";
    private static final String MODE_IMPORT_GENERATE = "import_generate";
    private static final int MAX_CARDS = 50;
    private static final int MAX_IMPORT_CARDS = 500;
    private static final int MIN_IMPORT_BATCH = 10;
    private static final int MAX_IMPORT_BATCH = 50;
    private static final int LOCAL_IMPORT_MIN_BATCH = 3;
    private static final int LOCAL_IMPORT_MAX_BATCH = 6;
    private static final int GENERATE_MAX_ATTEMPTS = 4;
    private static final int NOVELTY_HINT_LIMIT = 24;
    private static final int LOCAL_IMPORT_FULL_CONTEXT_CHARS = 2400;
    private static final int REMOTE_IMPORT_FULL_CONTEXT_CHARS = 7000;
    private static final int LOCAL_IMPORT_EXCERPT_HEAD_CHARS = 1100;
    private static final int LOCAL_IMPORT_EXCERPT_TAIL_CHARS = 800;
    private static final int REMOTE_IMPORT_EXCERPT_HEAD_CHARS = 2200;
    private static final int REMOTE_IMPORT_EXCERPT_TAIL_CHARS = 1600;
    private static final int LOCAL_IMPORT_OUTLINE_CHARS = 1200;
    private static final int REMOTE_IMPORT_OUTLINE_CHARS = 2600;
    private static final Duration VIDEO_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration VIDEO_POLL_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern RETRY_IN_PATTERN = Pattern.compile("retry in\\s*([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");
    private static final int DEFAULT_TTS_REQUESTS_PER_MINUTE = 60;
    private static final int DEFAULT_TTS_MAX_RETRIES = 5;
    private static final long DEFAULT_TTS_RETRY_INITIAL_DELAY_MS = 2000L;
    private static final long DEFAULT_TTS_RETRY_MAX_DELAY_MS = 30000L;
    private static final int DEFAULT_VISION_MAX_OUTPUT_TOKENS = 800;
    private static final String STEP_LOAD_SOURCE = "load_source";
    private static final String STEP_PREPARE_CONTEXT = "prepare_context";
    private static final String STEP_ANALYZE_CONTENT = "analyze_content";
    private static final String STEP_GENERATE_CONTENT = "generate_content";
    private static final String STEP_APPLY_CHANGES = "apply_changes";
    private static final String STEP_GENERATE_AUDIO = "generate_audio";
    private static final String STEP_GENERATE_MEDIA = "generate_media";

    private final OpenAiClient openAiClient;
    private final OpenAiProps props;
    private final SecretVault secretVault;
    private final AiProviderCredentialRepository credentialRepository;
    private final MediaApiClient mediaApiClient;
    private final AiImportContentService importContentService;
    private final AudioChunkingService audioChunkingService;
    private final CoreApiClient coreApiClient;
    private final CardNoveltyService noveltyService;
    private final ObjectMapper objectMapper;
    private final AnkiTemplateSupport ankiSupport;
    private final AiJobExecutionService executionService;
    private final int maxImportChars;
    private final String systemApiKey;
    private final Object ttsThrottleLock = new Object();
    private long nextTtsRequestAtMs = 0L;

    public OpenAiJobProcessor(OpenAiClient openAiClient,
                              OpenAiProps props,
                              SecretVault secretVault,
                              AiProviderCredentialRepository credentialRepository,
                              MediaApiClient mediaApiClient,
                              AiImportContentService importContentService,
                              AudioChunkingService audioChunkingService,
                              CoreApiClient coreApiClient,
                              CardNoveltyService noveltyService,
                              ObjectMapper objectMapper,
                              AiJobExecutionService executionService,
                              @Value("${app.ai.import.max-chars:200000}") int maxImportChars) {
        this.openAiClient = openAiClient;
        this.props = props;
        this.secretVault = secretVault;
        this.credentialRepository = credentialRepository;
        this.mediaApiClient = mediaApiClient;
        this.importContentService = importContentService;
        this.audioChunkingService = audioChunkingService;
        this.coreApiClient = coreApiClient;
        this.noveltyService = noveltyService;
        this.objectMapper = objectMapper;
        this.ankiSupport = new AnkiTemplateSupport(objectMapper);
        this.executionService = executionService;
        this.systemApiKey = props.systemApiKey() == null ? "" : props.systemApiKey().trim();
        this.maxImportChars = Math.max(maxImportChars, 1000);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public AiJobProcessingResult process(AiJobEntity job) {
        CredentialSelection credentialSelection = resolveCredential(job);
        String apiKey = credentialSelection.apiKey();
        if (credentialSelection.credential() != null) {
            AiProviderCredentialEntity credential = credentialSelection.credential();
            credential.setLastUsedAt(Instant.now());
            credential.setUpdatedAt(Instant.now());
            credentialRepository.save(credential);
        }

        executionService.resetPlan(job.getJobId(), resolveExecutionPlan(job));

        if (job.getType() == AiJobType.tts) {
            return runStep(job, safeParams(job), STEP_GENERATE_AUDIO, () -> handleTts(job, apiKey));
        }
        return handleText(job, apiKey);
    }

    private AiJobProcessingResult handleText(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String mode = params.path("mode").asText();
        if (MODE_AUDIT.equalsIgnoreCase(mode)) {
            return handleAudit(job, apiKey, params);
        }
        if (MODE_IMPORT_PREVIEW.equalsIgnoreCase(mode)) {
            return handleImportPreview(job, apiKey, params);
        }
        if (MODE_IMPORT_GENERATE.equalsIgnoreCase(mode)) {
            return handleImportGenerate(job, apiKey, params);
        }
        if (MODE_CARD_AUDIT.equalsIgnoreCase(mode)) {
            return handleCardAudit(job, apiKey, params);
        }
        if (MODE_CARD_MISSING_FIELDS.equalsIgnoreCase(mode)) {
            return handleCardMissingFields(job, apiKey, params);
        }
        if (MODE_CARD_MISSING_AUDIO.equalsIgnoreCase(mode)) {
            return handleCardMissingAudio(job, apiKey, params);
        }
        if (MODE_ENHANCE.equalsIgnoreCase(mode)) {
            String resolvedEnhanceMode = resolveEnhanceMode(params);
            if (MODE_MISSING_FIELDS.equals(resolvedEnhanceMode)) {
                return handleMissingFields(job, apiKey, params);
            }
            if (MODE_MISSING_AUDIO.equals(resolvedEnhanceMode)) {
                return handleMissingAudio(job, apiKey, params);
            }
            if (MODE_AUDIT.equals(resolvedEnhanceMode)) {
                return handleAudit(job, apiKey, params);
            }
        }
        if (MODE_MISSING_AUDIO.equalsIgnoreCase(mode)) {
            return handleMissingAudio(job, apiKey, params);
        }
        if (MODE_MISSING_FIELDS.equalsIgnoreCase(mode)) {
            return handleMissingFields(job, apiKey, params);
        }
        if (MODE_GENERATE_CARDS.equalsIgnoreCase(mode)) {
            return handleGenerateCardsMaybeBatched(job, apiKey, params);
        }
        return handleFreeformText(job, apiKey, params);
    }

    private List<String> resolveExecutionPlan(AiJobEntity job) {
        if (job == null) {
            return List.of();
        }
        if (job.getType() == AiJobType.tts) {
            return List.of(STEP_GENERATE_AUDIO);
        }
        JsonNode params = safeParams(job);
        String mode = params.path("mode").asText();
        if (MODE_IMPORT_PREVIEW.equalsIgnoreCase(mode)) {
            return List.of(STEP_LOAD_SOURCE, STEP_ANALYZE_CONTENT);
        }
        if (MODE_IMPORT_GENERATE.equalsIgnoreCase(mode)) {
            return List.of(STEP_LOAD_SOURCE, STEP_PREPARE_CONTEXT, STEP_GENERATE_CONTENT, STEP_GENERATE_MEDIA, STEP_GENERATE_AUDIO, STEP_APPLY_CHANGES);
        }
        if (MODE_AUDIT.equalsIgnoreCase(mode) || MODE_CARD_AUDIT.equalsIgnoreCase(mode)) {
            return List.of(STEP_PREPARE_CONTEXT, STEP_ANALYZE_CONTENT);
        }
        if (MODE_CARD_MISSING_AUDIO.equalsIgnoreCase(mode) || MODE_MISSING_AUDIO.equalsIgnoreCase(mode)) {
            return List.of(STEP_PREPARE_CONTEXT, STEP_GENERATE_AUDIO);
        }
        if (MODE_CARD_MISSING_FIELDS.equalsIgnoreCase(mode) || MODE_MISSING_FIELDS.equalsIgnoreCase(mode)) {
            return List.of(STEP_PREPARE_CONTEXT, STEP_GENERATE_CONTENT, STEP_APPLY_CHANGES);
        }
        if (MODE_GENERATE_CARDS.equalsIgnoreCase(mode)) {
            return List.of(STEP_PREPARE_CONTEXT, STEP_GENERATE_CONTENT, STEP_GENERATE_MEDIA, STEP_GENERATE_AUDIO, STEP_APPLY_CHANGES);
        }
        return List.of(STEP_GENERATE_CONTENT);
    }

    private <T> T runStep(AiJobEntity job,
                          JsonNode params,
                          String stepName,
                          AiJobExecutionService.StepOperation<T> operation) {
        if (job == null || shouldSkipStepTracking(params) || stepName == null || stepName.isBlank()) {
            try {
                return operation.run();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        }
        return executionService.runStep(job.getJobId(), stepName, operation);
    }

    private boolean shouldSkipStepTracking(JsonNode params) {
        return params != null && params.path("__skipStepTracking").asBoolean(false);
    }

    private AiJobProcessingResult handleImportPreview(AiJobEntity job, String apiKey, JsonNode params) {
        AiImportContentService.ImportTextPayload payload = runStep(job, params, STEP_LOAD_SOURCE, () -> loadImportPayload(job, apiKey, params));

        return runStep(job, params, STEP_ANALYZE_CONTENT, () -> {
            String prompt = buildImportPreviewPrompt(payload, params);
            JsonNode responseFormat = buildImportPreviewSchema(payload.maxRecommendedCards());
            String model = textOrDefault(params.path("model"), props.defaultModel());
            Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                    ? params.path("maxOutputTokens").asInt()
                    : null;

            OpenAiResponseResult response = openAiClient.createResponse(
                    apiKey,
                    new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
            );

            JsonNode parsed = parseJsonResponse(response.outputText());
            String summaryText = textOrDefault(parsed.path("summary"), "");
            int estimatedCount = resolveEstimatedCount(parsed.path("estimatedCount"), payload.maxRecommendedCards());

            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("mode", MODE_IMPORT_PREVIEW);
            summary.put("summary", summaryText);
            summary.put("estimatedCount", estimatedCount);
            summary.put("truncated", payload.truncated());
            summary.put("sourceBytes", payload.sizeBytes());
            summary.put("sourceChars", payload.charCount());
            summary.put("detectedCharset", payload.detectedCharset());
            summary.put("sourceType", resolveSourceType(payload.mimeType()));
            if (payload.extraction() != null) {
                summary.put("extraction", payload.extraction());
            }
            if (payload.sourcePages() != null) {
                summary.put("sourcePages", payload.sourcePages());
            }
            if (payload.ocrPages() != null) {
                summary.put("ocrPages", payload.ocrPages());
            }
            if (payload.audioDurationSeconds() != null) {
                summary.put("audioDurationSeconds", payload.audioDurationSeconds());
            }
            if (payload.audioChunks() != null) {
                summary.put("audioChunks", payload.audioChunks());
            }

            return new AiJobProcessingResult(
                    summary,
                    PROVIDER,
                    response.model(),
                    response.inputTokens(),
                    response.outputTokens(),
                    BigDecimal.ZERO,
                    job.getInputHash()
            );
        });
    }

    private AiJobProcessingResult handleImportGenerate(AiJobEntity job, String apiKey, JsonNode params) {
        AiImportContentService.ImportTextPayload payload = runStep(job, params, STEP_LOAD_SOURCE, () -> loadImportPayload(job, apiKey, params));
        boolean localOllamaRequest = isLocalOllamaRequest(params);
        ImportItemExtractor.ItemExtraction itemExtraction = ImportItemExtractor.extract(payload.text());
        List<ImportItemExtractor.SourceItem> items = itemExtraction.items();
        String sharedContext = buildImportSharedContext(payload, items, localOllamaRequest);

        ObjectNode updatedParams = params.isObject()
                ? ((ObjectNode) params).deepCopy()
                : objectMapper.createObjectNode();
        updatedParams.put("mode", MODE_GENERATE_CARDS);
        updatedParams.put("countLimit", MAX_IMPORT_CARDS);
        int requested = params.path("count").isInt() ? params.path("count").asInt() : 10;
        int total = Math.min(Math.max(requested, 1), MAX_IMPORT_CARDS);
        int batchSize = resolveImportBatchSize(params);
        if (!items.isEmpty()) {
            int available = Math.min(items.size(), MAX_IMPORT_CARDS);
            total = Math.min(total, available);
            List<ImportItemExtractor.SourceItem> requestedItems = items.subList(0, total);
            return handleGenerateCardsBatchedForItems(
                    job,
                    apiKey,
                    updatedParams,
                    requestedItems,
                    itemExtraction.missingNumbers(),
                    batchSize,
                    params,
                    payload.truncated(),
                    sharedContext
            );
        }
        updatedParams.put("input", buildImportGeneratePrompt(params, payload.truncated(), sharedContext));
        if (total <= batchSize) {
            updatedParams.put("count", total);
            return handleGenerateCards(job, apiKey, updatedParams);
        }
        return handleGenerateCardsBatched(job, apiKey, updatedParams, total, batchSize);
    }

    private AiJobProcessingResult handleGenerateCardsBatched(AiJobEntity job,
                                                             String apiKey,
                                                             ObjectNode baseParams,
                                                             int totalCount,
                                                             int batchSize) {
        String accessToken = job.getUserAccessToken();
        GenerationContext context = runStep(job, baseParams, STEP_PREPARE_CONTEXT, () -> prepareGenerationContext(job, baseParams, accessToken));
        String userPrompt = extractTextParam(baseParams, "input", "prompt", "text");
        GeneratedDraftBatch generated = runStep(job, baseParams, STEP_GENERATE_CONTENT, () -> generateDraftsBatched(
                job,
                apiKey,
                baseParams,
                context,
                totalCount,
                batchSize,
                (offset, count) -> userPrompt
        ));
        return applyGeneratedDrafts(job, apiKey, baseParams, context, generated, totalCount, accessToken);
    }

    private AiJobProcessingResult handleGenerateCardsBatchedForItems(AiJobEntity job,
                                                                     String apiKey,
                                                                     ObjectNode baseParams,
                                                                     List<ImportItemExtractor.SourceItem> items,
                                                                     List<Integer> missingNumbers,
                                                                     int batchSize,
                                                                     JsonNode params,
                                                                     boolean truncated,
                                                                     String sharedContext) {
        String accessToken = job.getUserAccessToken();
        GenerationContext context = runStep(job, baseParams, STEP_PREPARE_CONTEXT, () -> prepareGenerationContext(job, baseParams, accessToken));
        GeneratedDraftBatch generated = runStep(job, baseParams, STEP_GENERATE_CONTENT, () -> generateDraftsBatchedForItems(
                job,
                apiKey,
                baseParams,
                context,
                batchSize,
                items,
                missingNumbers,
                params,
                truncated,
                sharedContext
        ));
        return applyGeneratedDrafts(job, apiKey, baseParams, context, generated, items.size(), accessToken);
    }

    private String buildImportPreviewPrompt(AiImportContentService.ImportTextPayload payload, JsonNode params) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are an expert study assistant. ");
        builder.append("Summarize the source material in 3-6 sentences and estimate a reasonable number of flashcards to create. ");
        builder.append("Return JSON with keys summary and estimatedCount. ");
        builder.append("EstimatedCount must be a positive integer. ");
        String language = textOrNull(params.path("language"));
        if (language != null) {
            builder.append("Language hint: ").append(language).append(". ");
        }
        String instructions = textOrNull(params.path("instructions"));
        if (instructions != null) {
            builder.append("User instructions: ").append(instructions).append(". ");
        }
        if (payload.truncated()) {
            builder.append("Note: the source may be truncated. ");
        }
        builder.append("Source material:\n").append(payload.text());
        return builder.toString().trim();
    }

    private String buildImportGenerateItemsPrompt(List<ImportItemExtractor.SourceItem> items,
                                                  JsonNode params,
                                                  boolean truncated,
                                                  String sharedContext,
                                                  int offset,
                                                  int totalItems) {
        StringBuilder builder = new StringBuilder();
        if (sharedContext != null && !sharedContext.isBlank()) {
            builder.append(sharedContext).append("\n\n");
        }
        int batchStart = offset + 1;
        int batchEnd = offset + items.size();
        builder.append("You are generating flashcards from an ordered source. ");
        builder.append("Keep terminology, chronology, and dependencies consistent with the shared source context. ");
        builder.append("This batch covers items ").append(batchStart).append("-").append(batchEnd)
                .append(" out of ").append(totalItems).append(". ");
        builder.append("Items to convert into flashcards (use each item exactly once):");
        for (ImportItemExtractor.SourceItem item : items) {
            builder.append("\nsourceIndex=").append(item.sourceIndex()).append("; sourceText=").append(item.text());
        }
        builder.append("\nCreate one card per item. Return sourceIndex exactly as shown and sourceText exactly equal to the item text.");
        builder.append(" Use sourceText verbatim in the most relevant field.");
        builder.append(" Do not invent new items or repeat any item.");
        builder.append(" Preserve prerequisites, ordering, and technical details when the source is procedural.");
        String language = textOrNull(params.path("language"));
        if (language != null) {
            builder.append(" Language hint: ").append(language).append(".");
        }
        String instructions = textOrNull(params.path("instructions"));
        if (instructions != null) {
            builder.append(" User instructions: ").append(instructions).append(".");
        }
        if (truncated) {
            builder.append(" The item list may be truncated.");
        }
        return builder.toString().trim();
    }

    private String buildImportGeneratePrompt(JsonNode params, boolean truncated, String sharedContext) {
        StringBuilder builder = new StringBuilder();
        if (sharedContext != null && !sharedContext.isBlank()) {
            builder.append(sharedContext).append("\n\n");
        }
        builder.append("Create flashcards from this material. ");
        builder.append("Preserve terminology, chronology, dependencies, and step ordering when they matter. ");
        builder.append("If a selected field is missing, infer or create a helpful value.");
        String language = textOrNull(params.path("language"));
        if (language != null) {
            builder.append(" Language hint: ").append(language).append(".");
        }
        String instructions = textOrNull(params.path("instructions"));
        if (instructions != null) {
            builder.append(" User instructions: ").append(instructions).append(".");
        }
        if (truncated) {
            builder.append(" The source may be truncated.");
        }
        return builder.toString().trim();
    }

    private String buildImportSharedContext(AiImportContentService.ImportTextPayload payload,
                                            List<ImportItemExtractor.SourceItem> items,
                                            boolean localOllamaRequest) {
        if (payload == null || payload.text() == null || payload.text().isBlank()) {
            return "";
        }
        String text = payload.text().trim();
        int fullBudget = localOllamaRequest ? LOCAL_IMPORT_FULL_CONTEXT_CHARS : REMOTE_IMPORT_FULL_CONTEXT_CHARS;
        if (text.length() <= fullBudget) {
            return "Shared source context:\n" + text;
        }

        int headBudget = localOllamaRequest ? LOCAL_IMPORT_EXCERPT_HEAD_CHARS : REMOTE_IMPORT_EXCERPT_HEAD_CHARS;
        int tailBudget = localOllamaRequest ? LOCAL_IMPORT_EXCERPT_TAIL_CHARS : REMOTE_IMPORT_EXCERPT_TAIL_CHARS;
        int outlineBudget = localOllamaRequest ? LOCAL_IMPORT_OUTLINE_CHARS : REMOTE_IMPORT_OUTLINE_CHARS;
        StringBuilder builder = new StringBuilder();
        builder.append("Shared source context (condensed to fit the local model context window):");
        builder.append("\nBeginning excerpt:\n").append(text, 0, Math.min(headBudget, text.length()));
        if (text.length() > headBudget + 50) {
            int tailStart = Math.max(headBudget, text.length() - tailBudget);
            builder.append("\n...\nEnding excerpt:\n").append(text.substring(tailStart));
        }
        String outline = buildImportItemOutline(items, outlineBudget);
        if (!outline.isBlank()) {
            builder.append("\nOrdered outline:\n").append(outline);
        }
        return builder.toString().trim();
    }

    private String buildImportItemOutline(List<ImportItemExtractor.SourceItem> items, int maxChars) {
        if (items == null || items.isEmpty() || maxChars < 32) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            ImportItemExtractor.SourceItem item = items.get(i);
            String entry = item.sourceIndex() + ". " + item.text().trim();
            int projected = builder.length() + entry.length() + 1;
            if (projected > maxChars) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append("... (").append(items.size() - i).append(" more items)");
                break;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(entry);
        }
        return builder.toString();
    }

    private AiImportContentService.ImportTextPayload loadImportPayload(AiJobEntity job, String apiKey, JsonNode params) {
        UUID sourceMediaId = parseUuid(params.path("sourceMediaId").asText(null));
        String encoding = textOrNull(params.path("encoding"));
        String language = textOrNull(params.path("language"));
        String accessToken = job.getUserAccessToken();
        AiImportContentService.ImportSourcePayload source = importContentService.loadSource(sourceMediaId, accessToken);
        String mimeType = source.mimeType();
        if (mimeType == null) {
            throw new IllegalStateException("Source media type is missing");
        }
        if (source.truncated() && !mimeType.startsWith("text/")) {
            throw new IllegalStateException("Source file is too large to process. Please upload a smaller file.");
        }
        if (mimeType.startsWith("text/") || "application/pdf".equals(mimeType)) {
            return importContentService.extractText(source, encoding, language);
        }
        if (mimeType.startsWith("image/")) {
            return extractVisionPayload(apiKey, source, params);
        }
        if (mimeType.startsWith("audio/")) {
            return transcribeAudioPayload(apiKey, source, params, language);
        }
        throw new IllegalStateException("Unsupported source media type: " + mimeType);
    }

    private AiImportContentService.ImportTextPayload extractVisionPayload(String apiKey,
                                                                         AiImportContentService.ImportSourcePayload source,
                                                                         JsonNode params) {
        String model = textOrDefault(params.path("model"), props.defaultModel());
        String prompt = buildVisionExtractPrompt(params);
        JsonNode schema = buildVisionSchema();
        JsonNode input = buildVisionInput(prompt, source.bytes(), source.mimeType());

        OpenAiResponseResult response = openAiClient.createResponseWithInput(
                apiKey,
                model,
                input,
                DEFAULT_VISION_MAX_OUTPUT_TOKENS,
                schema
        );
        JsonNode parsed = parseJsonResponse(response.outputText());
        String extractedText = textOrDefault(parsed.path("text"), "");
        String description = textOrDefault(parsed.path("description"), "");
        String combined = buildVisionCombinedText(extractedText, description);
        if (combined.isBlank()) {
            throw new IllegalStateException("Image extraction returned empty text");
        }
        boolean truncated = false;
        if (combined.length() > maxImportChars) {
            combined = combined.substring(0, maxImportChars);
            truncated = true;
        }
        return new AiImportContentService.ImportTextPayload(
                combined,
                source.mimeType(),
                source.sizeBytes(),
                truncated,
                combined.length(),
                null,
                MAX_IMPORT_CARDS,
                "vision",
                null,
                null,
                null,
                null
        );
    }

    private AiImportContentService.ImportTextPayload transcribeAudioPayload(String apiKey,
                                                                            AiImportContentService.ImportSourcePayload source,
                                                                            JsonNode params,
                                                                            String language) {
        AudioChunkingService.AudioChunkingResult chunking = audioChunkingService.prepareChunks(source.bytes(), source.mimeType());
        String model = resolveSttModel(params);
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("STT model is required for audio import");
        }
        String sttLanguage = resolveSttLanguage(params, language);
        StringBuilder transcript = new StringBuilder();
        boolean truncated = false;
        int index = 0;
        for (AudioChunkingService.AudioChunk chunk : chunking.chunks()) {
            String fileName = resolveAudioFileName(index, chunk.mimeType());
            String chunkText = openAiClient.createTranscription(
                    apiKey,
                    new OpenAiTranscriptionRequest(
                            model,
                            sttLanguage,
                            "json",
                            chunk.mimeType(),
                            fileName,
                            chunk.bytes()
                    )
            );
            if (!chunkText.isBlank()) {
                if (!transcript.isEmpty()) {
                    transcript.append("\n");
                }
                transcript.append(chunkText.trim());
            }
            if (transcript.length() > maxImportChars) {
                transcript.setLength(maxImportChars);
                truncated = true;
                break;
            }
            index += 1;
        }
        if (transcript.isEmpty()) {
            throw new IllegalStateException("Audio transcription is empty");
        }
        return new AiImportContentService.ImportTextPayload(
                transcript.toString(),
                source.mimeType(),
                source.sizeBytes(),
                truncated,
                transcript.length(),
                null,
                MAX_IMPORT_CARDS,
                "stt",
                null,
                null,
                chunking.durationSeconds(),
                chunking.chunkCount()
        );
    }

    private String buildVisionExtractPrompt(JsonNode params) {
        StringBuilder builder = new StringBuilder();
        builder.append("Extract all visible text from this image and describe the non-text visual context. ");
        builder.append("Return JSON with keys text and description. ");
        builder.append("Text should preserve line breaks. Description should be 1-3 sentences. ");
        String language = textOrNull(params.path("language"));
        if (language != null) {
            builder.append("Language hint: ").append(language).append(". ");
        }
        return builder.toString().trim();
    }

    private JsonNode buildVisionSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string");
        properties.putObject("description").put("type", "string");
        schema.putArray("required").add("text").add("description");
        schema.put("additionalProperties", false);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "mnema_image_extract");
        responseFormat.set("schema", schema);
        responseFormat.put("strict", true);
        return responseFormat;
    }

    private JsonNode buildVisionInput(String prompt, byte[] imageBytes, String mimeType) {
        ArrayNode input = objectMapper.createArrayNode();
        ObjectNode message = input.addObject();
        message.put("type", "message");
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject()
                .put("type", "input_text")
                .put("text", prompt);
        ObjectNode imageNode = content.addObject();
        imageNode.put("type", "input_image");
        imageNode.put("detail", "auto");
        imageNode.put("image_url", buildDataUrl(imageBytes, mimeType));
        return input;
    }

    private String buildDataUrl(byte[] bytes, String mimeType) {
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        String type = (mimeType == null || mimeType.isBlank()) ? "application/octet-stream" : mimeType;
        return "data:" + type + ";base64," + base64;
    }

    private String buildVisionCombinedText(String extractedText, String description) {
        String cleanText = extractedText == null ? "" : extractedText.trim();
        String cleanDescription = description == null ? "" : description.trim();
        if (cleanText.isBlank() && cleanDescription.isBlank()) {
            return "";
        }
        if (cleanDescription.isBlank()) {
            return cleanText;
        }
        if (cleanText.isBlank()) {
            return "Visual context:\n" + cleanDescription;
        }
        return "Extracted text:\n" + cleanText + "\n\nVisual context:\n" + cleanDescription;
    }

    private String resolveSourceType(String mimeType) {
        if (mimeType == null) {
            return "unknown";
        }
        if (mimeType.startsWith("text/")) {
            return "text";
        }
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)) {
            return "docx";
        }
        if ("application/pdf".equals(mimeType)) {
            return "pdf";
        }
        if (mimeType.startsWith("image/")) {
            return "image";
        }
        if (mimeType.startsWith("audio/")) {
            return "audio";
        }
        return "file";
    }

    private String resolveSttModel(JsonNode params) {
        String model = textOrNull(params.path("stt").path("model"));
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        if (props.defaultSttModel() != null && !props.defaultSttModel().isBlank()) {
            return props.defaultSttModel();
        }
        return null;
    }

    private String resolveSttLanguage(JsonNode params, String fallback) {
        String language = textOrNull(params.path("stt").path("language"));
        if (language != null && !language.isBlank()) {
            return language.trim();
        }
        return fallback;
    }

    private String resolveAudioFileName(int index, String mimeType) {
        return "import-audio-" + index + resolveAudioExtension(mimeType);
    }

    private String resolveAudioExtension(String mimeType) {
        String normalized = normalizeMimeType(mimeType);
        if (normalized == null || normalized.isBlank()) {
            return ".bin";
        }
        return switch (normalized) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            case "audio/ogg" -> ".ogg";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/webm" -> ".webm";
            case "audio/flac" -> ".flac";
            default -> ".bin";
        };
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator).trim();
        }
        return normalized;
    }

    private JsonNode buildImportPreviewSchema(int maxCards) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("summary").put("type", "string");
        ObjectNode countNode = properties.putObject("estimatedCount");
        countNode.put("type", "integer");
        countNode.put("minimum", 1);
        ArrayNode required = schema.putArray("required");
        required.add("summary");
        required.add("estimatedCount");
        schema.put("additionalProperties", false);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "mnema_import_preview");
        responseFormat.set("schema", schema);
        responseFormat.put("strict", true);
        return responseFormat;
    }

    private int resolveEstimatedCount(JsonNode node, int maxCards) {
        int count = node != null && node.isInt() ? node.asInt() : 1;
        if (count < 1) {
            count = 1;
        }
        return count;
    }

    private AiJobProcessingResult handleMissingFields(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for missing field generation");
        }
        String accessToken = job.getUserAccessToken();
        record MissingFieldsContext(CoreTemplateResponse template,
                                    CorePublicDeckResponse publicDeck,
                                    String updateScope,
                                    Map<String, String> fieldTypes,
                                    List<String> promptFields,
                                    List<String> targetAudioFields,
                                    LinkedHashSet<String> targetFields,
                                    MissingCardSelection selection) {}

        MissingFieldsContext context = runStep(job, params, STEP_PREPARE_CONTEXT, () -> {
            CoreUserDeckResponse deck = coreApiClient.getUserDeck(job.getDeckId(), accessToken);
            if (deck.publicDeckId() == null) {
                throw new IllegalStateException("Deck template not found");
            }
            CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(deck.publicDeckId(), deck.currentVersion());
            if (publicDeck.templateId() == null) {
                throw new IllegalStateException("Template id not found");
            }
            Integer templateVersion = deck.templateVersion() != null ? deck.templateVersion() : publicDeck.templateVersion();
            CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), templateVersion, accessToken);
            String updateScope = resolveUpdateScope(job, deck, publicDeck, params);
            Map<String, String> fieldTypes = resolveFieldTypes(template);
            List<String> promptFields = resolveAllowedFields(params, template);
            List<String> audioFields = resolveAudioFields(template);
            List<String> targetAudioFields = resolveAudioTargetFields(params, audioFields);

            LinkedHashSet<String> targetFields = new LinkedHashSet<>();
            targetFields.addAll(promptFields);
            targetFields.addAll(targetAudioFields);
            if (targetFields.isEmpty()) {
                throw new IllegalStateException("No supported fields to generate");
            }

            MissingCardSelection selection = selectMissingCards(job.getDeckId(), targetFields, fieldTypes, params, accessToken);
            return new MissingFieldsContext(template, publicDeck, updateScope, fieldTypes, promptFields, targetAudioFields, targetFields, selection);
        });
        List<CoreUserCardResponse> missingCards = context.selection().cards();
        if (missingCards.isEmpty()) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("mode", MODE_MISSING_FIELDS);
            summary.put("updatedCards", 0);
            summary.put("candidates", 0);
            summary.put("deckId", job.getDeckId().toString());
            return new AiJobProcessingResult(
                    summary,
                    PROVIDER,
                    null,
                    0,
                    0,
                    BigDecimal.ZERO,
                    job.getInputHash()
            );
        }

        List<CoreUserCardResponse> promptCards = context.promptFields().isEmpty()
                ? List.of()
                : filterCardsForPrompt(missingCards, context.promptFields(), context.fieldTypes(), context.selection().allowedFieldsByCard());

        OpenAiResponseResult response = null;
        MediaApplyResult mediaResult = new MediaApplyResult(0, 0, 0);
        TtsApplyResult promptTtsResult = new TtsApplyResult(0, 0, null, null);
        if (!promptCards.isEmpty()) {
            String userPrompt = extractTextParam(params, "input", "prompt", "notes");
            response = runStep(job, params, STEP_GENERATE_CONTENT, () -> {
                String prompt = buildMissingFieldsPrompt(userPrompt, context.template(), context.publicDeck(), context.promptFields(), promptCards, job.getDeckId(), accessToken);
                ObjectNode responseFormat = buildMissingFieldsResponseFormat(context.promptFields());
                String model = textOrDefault(params.path("model"), props.defaultModel());
                Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                        ? params.path("maxOutputTokens").asInt()
                        : null;
                return openAiClient.createResponse(
                        apiKey,
                        new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
                );
            });

            JsonNode parsed = parseJsonResponse(response.outputText());
            List<MissingFieldUpdate> updates = parseMissingFieldUpdates(parsed, context.promptFields());
            ImageConfig imageConfig = resolveImageConfig(params, !context.promptFields().isEmpty());
            VideoConfig videoConfig = resolveVideoConfig(params, !context.promptFields().isEmpty());
            AtomicApplyResult applyResult = runStep(job, params, STEP_APPLY_CHANGES, () -> applyMissingFieldUpdatesAtomically(
                    job,
                    apiKey,
                    accessToken,
                    params,
                    context.template(),
                    promptCards,
                    updates,
                    context.fieldTypes(),
                    context.selection().allowedFieldsByCard(),
                    imageConfig,
                    videoConfig,
                    context.targetAudioFields(),
                    context.updateScope()
            ));
            mediaResult = applyResult.mediaResult();
            promptTtsResult = applyResult.ttsResult();
        }

        TtsApplyResult ttsResult = promptTtsResult;
        String ttsError = promptTtsResult.error();
        if (!context.targetAudioFields().isEmpty()) {
            if (!params.path("tts").path("enabled").asBoolean(false)) {
                ttsError = "TTS settings are required for audio fields";
            } else {
                Set<UUID> promptCardIds = promptCards.stream()
                        .map(CoreUserCardResponse::userCardId)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                List<CoreUserCardResponse> audioCards = filterCardsForAudio(
                        missingCards.stream()
                                .filter(card -> card == null || card.userCardId() == null || !promptCardIds.contains(card.userCardId()))
                                .toList(),
                        context.targetAudioFields(),
                        context.selection().allowedFieldsByCard()
                );
                if (!audioCards.isEmpty()) {
                    TtsApplyResult additionalTtsResult = runStep(job, params, STEP_GENERATE_AUDIO, () -> applyTtsForMissingAudio(
                            job,
                            apiKey,
                            params,
                            audioCards,
                            context.template(),
                            context.targetAudioFields(),
                            context.updateScope()
                    ));
                    ttsResult = mergeTtsResults(ttsResult, additionalTtsResult);
                    if (ttsError == null && additionalTtsResult.error() != null) {
                        ttsError = additionalTtsResult.error();
                    }
                }
            }
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_MISSING_FIELDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("updatedCards", mediaResult.updatedCards());
        summary.put("candidates", missingCards.size());
        if (mediaResult.imagesGenerated() > 0) {
            summary.put("imagesGenerated", mediaResult.imagesGenerated());
        }
        if (mediaResult.videosGenerated() > 0) {
            summary.put("videosGenerated", mediaResult.videosGenerated());
        }
        if (!context.targetAudioFields().isEmpty()) {
            summary.put("ttsGenerated", ttsResult.generated());
            summary.put("ttsUpdatedCards", ttsResult.updatedCards());
            if (ttsResult.charsGenerated() > 0) {
                summary.put("ttsCharsGenerated", ttsResult.charsGenerated());
            }
            if (ttsError != null) {
                summary.put("ttsError", ttsError);
            }
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        context.targetFields().forEach(fieldsNode::add);
        summary.set("items", buildEnhanceItems(missingCards, mediaResult, ttsResult));

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response == null ? null : response.model(),
                response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash(),
                resolveFinalStatus(ttsError != null
                        || !ttsResult.cardErrors().isEmpty()
                        || !mediaResult.cardErrors().isEmpty())
        );
    }

    private AiJobProcessingResult handleMissingAudio(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for missing audio generation");
        }
        if (!params.path("tts").path("enabled").asBoolean(false)) {
            throw new IllegalStateException("TTS settings are required for missing audio generation");
        }
        String accessToken = job.getUserAccessToken();
        record MissingAudioContext(CoreTemplateResponse template, String updateScope, List<String> targetFields, List<CoreUserCardResponse> missingCards) {}
        MissingAudioContext context = runStep(job, params, STEP_PREPARE_CONTEXT, () -> {
            CoreUserDeckResponse deck = coreApiClient.getUserDeck(job.getDeckId(), accessToken);
            if (deck.publicDeckId() == null) {
                throw new IllegalStateException("Deck template not found");
            }
            CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(deck.publicDeckId(), deck.currentVersion());
            if (publicDeck.templateId() == null) {
                throw new IllegalStateException("Template id not found");
            }
            Integer templateVersion = deck.templateVersion() != null ? deck.templateVersion() : publicDeck.templateVersion();
            CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), templateVersion, accessToken);
            String updateScope = resolveUpdateScope(job, deck, publicDeck, params);
            List<String> audioFields = resolveAudioFields(template);
            if (audioFields.isEmpty()) {
                throw new IllegalStateException("No audio fields available");
            }
            List<String> targetFields = resolveAudioTargetFields(params, audioFields);
            if (targetFields.isEmpty()) {
                throw new IllegalStateException("No audio fields selected");
            }
            Map<String, String> fieldTypes = resolveFieldTypes(template);
            List<CoreUserCardResponse> missingCards = selectMissingCards(
                    job.getDeckId(),
                    new LinkedHashSet<>(targetFields),
                    fieldTypes,
                    params,
                    accessToken
            ).cards();
            return new MissingAudioContext(template, updateScope, targetFields, missingCards);
        });
        List<CoreUserCardResponse> missingCards = context.missingCards();
        if (missingCards.isEmpty()) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("mode", MODE_MISSING_AUDIO);
            summary.put("updatedCards", 0);
            summary.put("ttsGenerated", 0);
            summary.put("candidates", 0);
            summary.put("deckId", job.getDeckId().toString());
            return new AiJobProcessingResult(
                    summary,
                    PROVIDER,
                    null,
                    null,
                    null,
                    BigDecimal.ZERO,
                    job.getInputHash()
            );
        }

        TtsApplyResult ttsResult = runStep(job, params, STEP_GENERATE_AUDIO, () -> applyTtsForMissingAudio(
                job,
                apiKey,
                params,
                missingCards,
                context.template(),
                context.targetFields(),
                context.updateScope()
        ));

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_MISSING_AUDIO);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("updatedCards", ttsResult.updatedCards());
        summary.put("ttsGenerated", ttsResult.generated());
        if (ttsResult.charsGenerated() > 0) {
            summary.put("ttsCharsGenerated", ttsResult.charsGenerated());
        }
        summary.put("candidates", missingCards.size());
        if (ttsResult.error() != null) {
            summary.put("ttsError", ttsResult.error());
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        context.targetFields().forEach(fieldsNode::add);
        summary.set("items", buildEnhanceItems(missingCards, new MediaApplyResult(0, 0, 0), ttsResult));

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                ttsResult.model(),
                null,
                null,
                BigDecimal.ZERO,
                job.getInputHash(),
                resolveFinalStatus(ttsResult.error() != null || !ttsResult.cardErrors().isEmpty())
        );
    }

    private AiJobProcessingResult handleAudit(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for audit");
        }
        String accessToken = job.getUserAccessToken();
        record AuditContextData(CoreTemplateResponse template,
                                CorePublicDeckResponse publicDeck,
                                List<CoreUserCardResponse> cards,
                                List<String> targetFields,
                                AuditAnalyzer.AuditContext analysis) {}
        AuditContextData context = runStep(job, params, STEP_PREPARE_CONTEXT, () -> {
            CoreUserDeckResponse deck = coreApiClient.getUserDeck(job.getDeckId(), accessToken);
            if (deck.publicDeckId() == null) {
                throw new IllegalStateException("Deck template not found");
            }
            CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(deck.publicDeckId(), deck.currentVersion());
            if (publicDeck.templateId() == null) {
                throw new IllegalStateException("Template id not found");
            }
            Integer templateVersion = deck.templateVersion() != null ? deck.templateVersion() : publicDeck.templateVersion();
            CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), templateVersion, accessToken);
            int sampleLimit = resolveAuditSampleLimit(params.path("sampleLimit"));
            List<CoreUserCardResponse> cards = coreApiClient.getUserCards(job.getDeckId(), 1, sampleLimit, accessToken).content();
            List<String> targetFields = resolveAllowedFields(params, template);
            if (targetFields.isEmpty()) {
                throw new IllegalStateException("No supported fields to audit");
            }
            AuditAnalyzer.AuditContext analysis = AuditAnalyzer.analyze(objectMapper, template, cards, targetFields);
            return new AuditContextData(template, publicDeck, cards, targetFields, analysis);
        });

        OpenAiResponseResult response = runStep(job, params, STEP_ANALYZE_CONTENT, () -> {
            String prompt = buildAuditPrompt(params, context.template(), context.publicDeck(), context.targetFields(), context.cards(), context.analysis());
            ObjectNode responseFormat = buildAuditResponseFormat();
            String model = textOrDefault(params.path("model"), props.defaultModel());
            Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                    ? params.path("maxOutputTokens").asInt()
                    : null;
            return openAiClient.createResponse(
                    apiKey,
                    new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
            );
        });

        JsonNode parsed = parseJsonResponse(response.outputText());
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_AUDIT);
        summary.put("deckId", job.getDeckId().toString());
        summary.set("auditStats", context.analysis().summary());
        summary.set("auditIssues", context.analysis().issues());
        summary.set("aiSummary", parsed);

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response.model(),
                response.inputTokens(),
                response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleCardAudit(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for card audit");
        }
        UUID cardId = parseUuid(params.path("cardId").asText(null));
        if (cardId == null) {
            throw new IllegalStateException("Card id is required for card audit");
        }
        String accessToken = job.getUserAccessToken();
        record CardAuditContext(CorePublicDeckResponse publicDeck,
                                CoreTemplateResponse template,
                                CoreApiClient.CoreUserCardDetail card) {}
        CardAuditContext context = runStep(job, params, STEP_PREPARE_CONTEXT, () -> {
            CoreUserDeckResponse deck = coreApiClient.getUserDeck(job.getDeckId(), accessToken);
            if (deck.publicDeckId() == null) {
                throw new IllegalStateException("Deck template not found");
            }
            CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(deck.publicDeckId(), deck.currentVersion());
            if (publicDeck.templateId() == null) {
                throw new IllegalStateException("Template id not found");
            }
            Integer templateVersion = deck.templateVersion() != null ? deck.templateVersion() : publicDeck.templateVersion();
            CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), templateVersion, accessToken);
            CoreApiClient.CoreUserCardDetail card = coreApiClient.getUserCard(job.getDeckId(), cardId, accessToken);
            return new CardAuditContext(publicDeck, template, card);
        });

        OpenAiResponseResult response = runStep(job, params, STEP_ANALYZE_CONTENT, () -> {
            String prompt = buildCardAuditPrompt(context.publicDeck(), context.template(), context.card());
            ObjectNode responseFormat = buildCardAuditResponseFormat();
            String model = textOrDefault(params.path("model"), props.defaultModel());
            Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                    ? params.path("maxOutputTokens").asInt()
                    : null;
            return openAiClient.createResponse(
                    apiKey,
                    new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
            );
        });

        JsonNode parsed = parseJsonResponse(response.outputText());
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_CARD_AUDIT);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("cardId", cardId.toString());
        summary.set("aiSummary", parsed);

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response.model(),
                response.inputTokens(),
                response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleCardMissingFields(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for card missing fields");
        }
        UUID cardId = parseUuid(params.path("cardId").asText(null));
        if (cardId == null) {
            throw new IllegalStateException("Card id is required for card missing fields");
        }
        String accessToken = job.getUserAccessToken();
        record CardMissingFieldsContext(CorePublicDeckResponse publicDeck,
                                        CoreTemplateResponse template,
                                        String updateScope,
                                        Map<String, String> fieldTypes,
                                        List<String> promptFields,
                                        List<String> targetAudioFields,
                                        CoreApiClient.CoreUserCardDetail card) {}
        CardMissingFieldsContext context = runStep(job, params, STEP_PREPARE_CONTEXT, () -> {
            CoreUserDeckResponse deck = coreApiClient.getUserDeck(job.getDeckId(), accessToken);
            if (deck.publicDeckId() == null) {
                throw new IllegalStateException("Deck template not found");
            }
            CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(deck.publicDeckId(), deck.currentVersion());
            if (publicDeck.templateId() == null) {
                throw new IllegalStateException("Template id not found");
            }
            Integer templateVersion = deck.templateVersion() != null ? deck.templateVersion() : publicDeck.templateVersion();
            CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), templateVersion, accessToken);
            String updateScope = resolveUpdateScope(job, deck, publicDeck, params);
            Map<String, String> fieldTypes = resolveFieldTypes(template);
            List<String> promptFields = resolveAllowedFields(params, template);
            List<String> audioFields = resolveAudioFields(template);
            List<String> targetAudioFields = resolveAudioTargetFields(params, audioFields);
            if (promptFields.isEmpty() && targetAudioFields.isEmpty()) {
                throw new IllegalStateException("No supported fields to generate");
            }
            CoreApiClient.CoreUserCardDetail card = coreApiClient.getUserCard(job.getDeckId(), cardId, accessToken);
            if (card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                throw new IllegalStateException("Card content is empty");
            }
            return new CardMissingFieldsContext(publicDeck, template, updateScope, fieldTypes, promptFields, targetAudioFields, card);
        });

        OpenAiResponseResult response = null;
        MediaApplyResult mediaResult = new MediaApplyResult(0, 0, 0);
        TtsApplyResult ttsResult = new TtsApplyResult(0, 0, null, null);
        if (!context.promptFields().isEmpty()) {
            response = runStep(job, params, STEP_GENERATE_CONTENT, () -> {
                String prompt = buildCardMissingFieldsPrompt(context.publicDeck(), context.template(), context.card(), context.promptFields());
                ObjectNode responseFormat = buildCardMissingFieldsResponseFormat(context.promptFields());
                String model = textOrDefault(params.path("model"), props.defaultModel());
                Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                        ? params.path("maxOutputTokens").asInt()
                        : null;
                return openAiClient.createResponse(
                        apiKey,
                        new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
                );
            });

            JsonNode parsed = parseJsonResponse(response.outputText());
            ImageConfig imageConfig = resolveImageConfig(params, true);
            VideoConfig videoConfig = resolveVideoConfig(params, true);
            AtomicApplyResult applyResult = runStep(job, params, STEP_APPLY_CHANGES, () -> applyCardMissingFieldUpdateAtomically(
                    job,
                    apiKey,
                    accessToken,
                    params,
                    context.template(),
                    context.card(),
                    parsed,
                    context.promptFields(),
                    context.fieldTypes(),
                    imageConfig,
                    videoConfig,
                    context.targetAudioFields(),
                    context.updateScope()
            ));
            mediaResult = applyResult.mediaResult();
            ttsResult = applyResult.ttsResult();
        }

        String ttsError = ttsResult.error();
        if (!context.targetAudioFields().isEmpty()) {
            if (!params.path("tts").path("enabled").asBoolean(false)) {
                ttsError = "TTS settings are required for audio fields";
            } else if (context.promptFields().isEmpty()) {
                CoreUserCardResponse cardResponse = new CoreUserCardResponse(
                        context.card().userCardId(),
                        context.card().publicCardId(),
                        context.card().isCustom(),
                        context.card().effectiveContent()
                );
                List<CoreUserCardResponse> audioCards = filterCardsForAudio(List.of(cardResponse), context.targetAudioFields(), Map.of());
                if (!audioCards.isEmpty()) {
                    ttsResult = runStep(job, params, STEP_GENERATE_AUDIO, () -> applyTtsForMissingAudio(
                            job,
                            apiKey,
                            params,
                            audioCards,
                            context.template(),
                            context.targetAudioFields(),
                            context.updateScope()
                    ));
                    if (ttsError == null && ttsResult.error() != null) {
                        ttsError = ttsResult.error();
                    }
                }
            }
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_CARD_MISSING_FIELDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("cardId", cardId.toString());
        summary.put("updated", mediaResult.updatedCards());
        if (mediaResult.imagesGenerated() > 0) {
            summary.put("imagesGenerated", mediaResult.imagesGenerated());
        }
        if (mediaResult.videosGenerated() > 0) {
            summary.put("videosGenerated", mediaResult.videosGenerated());
        }
        if (!context.targetAudioFields().isEmpty()) {
            summary.put("ttsGenerated", ttsResult.generated());
            summary.put("ttsUpdatedCards", ttsResult.updatedCards());
            if (ttsResult.charsGenerated() > 0) {
                summary.put("ttsCharsGenerated", ttsResult.charsGenerated());
            }
            if (ttsError != null) {
                summary.put("ttsError", ttsError);
            }
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        LinkedHashSet<String> allFields = new LinkedHashSet<>();
        allFields.addAll(context.promptFields());
        allFields.addAll(context.targetAudioFields());
        allFields.forEach(fieldsNode::add);
        CoreUserCardResponse cardResponse = new CoreUserCardResponse(
                context.card().userCardId(),
                context.card().publicCardId(),
                context.card().isCustom(),
                context.card().effectiveContent()
        );
        summary.set("items", buildEnhanceItems(List.of(cardResponse), mediaResult, ttsResult));

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response == null ? null : response.model(),
                response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash(),
                resolveFinalStatus(ttsError != null
                        || !ttsResult.cardErrors().isEmpty()
                        || !mediaResult.cardErrors().isEmpty())
        );
    }

    private AiJobProcessingResult handleCardMissingAudio(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for card missing audio generation");
        }
        UUID cardId = parseUuid(params.path("cardId").asText(null));
        if (cardId == null) {
            throw new IllegalStateException("Card id is required for card missing audio generation");
        }
        if (!params.path("tts").path("enabled").asBoolean(false)) {
            throw new IllegalStateException("TTS settings are required for missing audio generation");
        }
        String accessToken = job.getUserAccessToken();
        record CardMissingAudioContext(CoreTemplateResponse template,
                                       String updateScope,
                                       CoreApiClient.CoreUserCardDetail card,
                                       List<String> missingTargets,
                                       List<String> targetFields) {}
        CardMissingAudioContext context = runStep(job, params, STEP_PREPARE_CONTEXT, () -> {
            CoreUserDeckResponse deck = coreApiClient.getUserDeck(job.getDeckId(), accessToken);
            if (deck.publicDeckId() == null) {
                throw new IllegalStateException("Deck template not found");
            }
            CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(deck.publicDeckId(), deck.currentVersion());
            if (publicDeck.templateId() == null) {
                throw new IllegalStateException("Template id not found");
            }
            Integer templateVersion = deck.templateVersion() != null ? deck.templateVersion() : publicDeck.templateVersion();
            CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), templateVersion, accessToken);
            String updateScope = resolveUpdateScope(job, deck, publicDeck, params);
            List<String> audioFields = resolveAudioFields(template);
            if (audioFields.isEmpty()) {
                throw new IllegalStateException("No audio fields available");
            }
            List<String> targetFields = resolveAudioTargetFields(params, audioFields);
            if (targetFields.isEmpty()) {
                throw new IllegalStateException("No audio fields selected");
            }

            CoreApiClient.CoreUserCardDetail card = coreApiClient.getUserCard(job.getDeckId(), cardId, accessToken);
            if (card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                throw new IllegalStateException("Card content is empty");
            }
            List<String> missingTargets = targetFields.stream()
                .filter(field -> isMissingAudio(card.effectiveContent().get(field)))
                .toList();
            return new CardMissingAudioContext(template, updateScope, card, missingTargets, targetFields);
        });
        List<String> missingTargets = context.missingTargets();
        if (missingTargets.isEmpty()) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("mode", MODE_CARD_MISSING_AUDIO);
            summary.put("deckId", job.getDeckId().toString());
            summary.put("cardId", cardId.toString());
            summary.put("updatedCards", 0);
            summary.put("ttsGenerated", 0);
            ArrayNode fieldsNode = summary.putArray("fields");
            context.targetFields().forEach(fieldsNode::add);
            return new AiJobProcessingResult(
                    summary,
                    PROVIDER,
                    null,
                    null,
                    null,
                    BigDecimal.ZERO,
                    job.getInputHash()
            );
        }

        TtsApplyResult ttsResult;
        String ttsError = null;
        try {
            CoreUserCardResponse cardResponse = new CoreUserCardResponse(
                    context.card().userCardId(),
                    context.card().publicCardId(),
                    context.card().isCustom(),
                    context.card().effectiveContent()
            );
            ttsResult = runStep(job, params, STEP_GENERATE_AUDIO, () -> applyTtsForMissingAudio(
                    job,
                    apiKey,
                    params,
                    List.of(cardResponse),
                    context.template(),
                    missingTargets,
                    context.updateScope()
            ));
        } catch (Exception ex) {
            ttsResult = new TtsApplyResult(0, 0, null, null);
            ttsError = summarizeError(ex);
        }
        if (ttsError == null && ttsResult.error() != null) {
            ttsError = ttsResult.error();
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_CARD_MISSING_AUDIO);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("cardId", cardId.toString());
        summary.put("updatedCards", ttsResult.updatedCards());
        summary.put("ttsGenerated", ttsResult.generated());
        if (ttsResult.charsGenerated() > 0) {
            summary.put("ttsCharsGenerated", ttsResult.charsGenerated());
        }
        if (ttsError != null) {
            summary.put("ttsError", ttsError);
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        missingTargets.forEach(fieldsNode::add);
        CoreUserCardResponse cardResponse = new CoreUserCardResponse(
                context.card().userCardId(),
                context.card().publicCardId(),
                context.card().isCustom(),
                context.card().effectiveContent()
        );
        summary.set("items", buildEnhanceItems(List.of(cardResponse), new MediaApplyResult(0, 0, 0), ttsResult));

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                ttsResult.model(),
                null,
                null,
                BigDecimal.ZERO,
                job.getInputHash(),
                resolveFinalStatus(ttsError != null || !ttsResult.cardErrors().isEmpty())
        );
    }

    private AiJobProcessingResult handleFreeformText(AiJobEntity job, String apiKey, JsonNode params) {
        String input = extractTextParam(params, "input", "prompt", "text");
        if (input == null || input.isBlank()) {
            input = params.isMissingNode() ? "" : params.toString();
        }
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        String finalInput = input;
        OpenAiResponseResult response = runStep(job, params, STEP_GENERATE_CONTENT, () -> openAiClient.createResponse(
                apiKey,
                new OpenAiResponseRequest(model, finalInput, maxOutputTokens, null)
        ));

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("text", response.outputText());
        summary.put("model", response.model());
        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response.model(),
                response.inputTokens(),
                response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleGenerateCards(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for card generation");
        }
        String accessToken = job.getUserAccessToken();
        GenerationContext context = runStep(job, params, STEP_PREPARE_CONTEXT, () -> prepareGenerationContext(job, params, accessToken));
        String userPrompt = extractTextParam(params, "input", "prompt", "text");
        GeneratedDraftBatch generated = runStep(job, params, STEP_GENERATE_CONTENT, () -> generateDrafts(
                job,
                apiKey,
                params,
                context,
                context.count(),
                userPrompt,
                true
        ));
        return applyGeneratedDrafts(job, apiKey, params, context, generated, context.count(), accessToken);
    }

    private GenerationContext prepareGenerationContext(AiJobEntity job, JsonNode params, String accessToken) {
        CoreUserDeckResponse deck = coreApiClient.getUserDeck(job.getDeckId(), accessToken);
        if (deck.publicDeckId() == null) {
            throw new IllegalStateException("Deck template not found");
        }
        CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(deck.publicDeckId(), deck.currentVersion());
        if (publicDeck.templateId() == null) {
            throw new IllegalStateException("Template id not found");
        }
        Integer templateVersion = deck.templateVersion() != null ? deck.templateVersion() : publicDeck.templateVersion();
        CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), templateVersion, accessToken);
        String updateScope = resolveUpdateScope(job, deck, publicDeck, params);
        int count = resolveCount(params);
        List<String> allowedFields = resolveAllowedFields(params, template);
        if (allowedFields.isEmpty()) {
            throw new IllegalStateException("No supported fields to generate");
        }
        Map<String, String> fieldTypes = resolveFieldTypes(template);
        CardNoveltyService.NoveltyIndex noveltyIndex = noveltyService.buildIndex(job.getDeckId(), accessToken, allowedFields);
        String fewShotExamples = isLocalOllamaRequest(params) ? "" : buildFewShotExamples(job.getDeckId(), accessToken, allowedFields);
        return new GenerationContext(deck, publicDeck, template, updateScope, count, allowedFields, fieldTypes, noveltyIndex, fewShotExamples);
    }

    private GeneratedDraftBatch generateDrafts(AiJobEntity job,
                                               String apiKey,
                                               JsonNode params,
                                               GenerationContext context,
                                               int requestedCount,
                                               String userPrompt,
                                               boolean expandCandidateCount) {
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;
        boolean localOllamaRequest = isLocalOllamaRequest(params);
        List<CardDraft> uniqueDrafts = new ArrayList<>();
        int droppedEmpty = 0;
        int droppedExact = 0;
        int droppedPrimary = 0;
        int droppedSemantic = 0;
        Integer totalTokensIn = null;
        Integer totalTokensOut = null;
        String responseModel = null;
        List<UsageEvent> usageEvents = new ArrayList<>();

        for (int attempt = 0; attempt < GENERATE_MAX_ATTEMPTS && uniqueDrafts.size() < requestedCount; attempt++) {
            int remaining = requestedCount - uniqueDrafts.size();
            int candidateCount = expandCandidateCount
                    ? resolveCandidateCount(remaining, attempt, localOllamaRequest)
                    : remaining;
            String prompt = buildCardsPrompt(
                    augmentGeneratePrompt(userPrompt, context.noveltyIndex(), attempt, localOllamaRequest),
                    context.template(),
                    context.publicDeck(),
                    context.allowedFields(),
                    candidateCount,
                    context.fewShotExamples(),
                    localOllamaRequest
            );
            JsonNode responseFormat = buildCardsSchema(context.allowedFields(), candidateCount);
            long startedAtNs = System.nanoTime();
            OpenAiResponseResult response = openAiClient.createResponse(
                    apiKey,
                    new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
            );
            long durationMs = elapsedMillis(startedAtNs);
            responseModel = response.model();
            totalTokensIn = sumNullable(totalTokensIn, response.inputTokens());
            totalTokensOut = sumNullable(totalTokensOut, response.outputTokens());
            usageEvents.add(buildUsageEvent(
                    STEP_GENERATE_CONTENT,
                    attempt,
                    remaining,
                    candidateCount,
                    response,
                    durationMs
            ));

            JsonNode parsed = parseJsonResponse(response.outputText());
            List<CardDraft> drafts = buildCardDrafts(parsed, context.allowedFields(), context.template(), context.fieldTypes());
            CardNoveltyService.FilterResult<CardDraft> filtered = noveltyService.filterCandidates(
                    drafts,
                    CardDraft::content,
                    context.allowedFields(),
                    context.noveltyIndex(),
                    remaining
            );
            uniqueDrafts.addAll(filtered.accepted());
            droppedEmpty += filtered.droppedEmpty();
            droppedExact += filtered.droppedExact();
            droppedPrimary += filtered.droppedPrimary();
            droppedSemantic += filtered.droppedSemantic();
        }

        if (uniqueDrafts.size() < requestedCount) {
            throw new IllegalStateException("Failed to generate enough unique cards. Try a more specific prompt.");
        }

        return new GeneratedDraftBatch(
                responseModel,
                totalTokensIn,
                totalTokensOut,
                uniqueDrafts.stream().limit(requestedCount).toList(),
                droppedEmpty,
                droppedExact,
                droppedPrimary,
                droppedSemantic,
                null,
                List.copyOf(usageEvents)
        );
    }

    private GeneratedDraftBatch generateDraftsBatched(AiJobEntity job,
                                                      String apiKey,
                                                      JsonNode params,
                                                      GenerationContext context,
                                                      int totalCount,
                                                      int batchSize,
                                                      BatchPromptFactory promptFactory) {
        return generateDraftsBatched(job, apiKey, params, context, totalCount, batchSize, promptFactory, true);
    }

    private GeneratedDraftBatch generateDraftsBatched(AiJobEntity job,
                                                      String apiKey,
                                                      JsonNode params,
                                                      GenerationContext context,
                                                      int totalCount,
                                                      int batchSize,
                                                      BatchPromptFactory promptFactory,
                                                      boolean expandCandidateCount) {
        int remaining = totalCount;
        int offset = 0;
        List<CardDraft> drafts = new ArrayList<>();
        int droppedEmpty = 0;
        int droppedExact = 0;
        int droppedPrimary = 0;
        int droppedSemantic = 0;
        Integer totalTokensIn = null;
        Integer totalTokensOut = null;
        String model = null;
        List<UsageEvent> usageEvents = new ArrayList<>();

        while (remaining > 0) {
            int count = Math.min(batchSize, remaining);
            GeneratedDraftBatch batch = generateDrafts(
                    job,
                    apiKey,
                    params,
                    context,
                    count,
                    promptFactory.build(offset, count),
                    expandCandidateCount
            );
            drafts.addAll(batch.drafts());
            droppedEmpty += batch.droppedEmpty();
            droppedExact += batch.droppedExact();
            droppedPrimary += batch.droppedPrimary();
            droppedSemantic += batch.droppedSemantic();
            totalTokensIn = sumNullable(totalTokensIn, batch.inputTokens());
            totalTokensOut = sumNullable(totalTokensOut, batch.outputTokens());
            usageEvents.addAll(batch.usageEvents());
            if (model == null) {
                model = batch.model();
            }
            remaining -= count;
            offset += count;
            executionService.updateStepProgress(
                    job.getJobId(),
                    STEP_GENERATE_CONTENT,
                    offset / (double) Math.max(1, totalCount)
            );
        }

        return new GeneratedDraftBatch(
                model,
                totalTokensIn,
                totalTokensOut,
                List.copyOf(drafts),
                droppedEmpty,
                droppedExact,
                droppedPrimary,
                droppedSemantic,
                null,
                List.copyOf(usageEvents)
        );
    }

    private GeneratedDraftBatch generateDraftsBatchedForItems(AiJobEntity job,
                                                              String apiKey,
                                                              JsonNode params,
                                                              GenerationContext context,
                                                              int batchSize,
                                                              List<ImportItemExtractor.SourceItem> items,
                                                              List<Integer> missingNumbers,
                                                              JsonNode originalParams,
                                                              boolean truncated,
                                                              String sharedContext) {
        int remaining = items.size();
        int offset = 0;
        List<CardDraft> drafts = new ArrayList<>();
        Integer totalTokensIn = null;
        Integer totalTokensOut = null;
        String model = null;
        List<UsageEvent> usageEvents = new ArrayList<>();

        while (remaining > 0) {
            int count = Math.min(batchSize, remaining);
            List<ImportItemExtractor.SourceItem> batchItems = items.subList(offset, offset + count);
            GeneratedDraftBatch batch = generateDraftsForItems(
                    job,
                    apiKey,
                    params,
                    context,
                    batchItems,
                    buildImportGenerateItemsPrompt(
                            batchItems,
                            originalParams,
                            truncated,
                            sharedContext,
                            offset,
                            items.size()
                    )
            );
            drafts.addAll(batch.drafts());
            totalTokensIn = sumNullable(totalTokensIn, batch.inputTokens());
            totalTokensOut = sumNullable(totalTokensOut, batch.outputTokens());
            usageEvents.addAll(batch.usageEvents());
            if (model == null) {
                model = batch.model();
            }
            remaining -= count;
            offset += count;
            executionService.updateStepProgress(
                    job.getJobId(),
                    STEP_GENERATE_CONTENT,
                    offset / (double) Math.max(1, items.size())
            );
        }

        return new GeneratedDraftBatch(
                model,
                totalTokensIn,
                totalTokensOut,
                List.copyOf(drafts),
                0,
                0,
                0,
                0,
                new SourceCoverage(items.size(), drafts.size(), List.of(), 0, missingNumbers == null ? List.of() : List.copyOf(missingNumbers)),
                List.copyOf(usageEvents)
        );
    }

    private GeneratedDraftBatch generateDraftsForItems(AiJobEntity job,
                                                       String apiKey,
                                                       JsonNode params,
                                                       GenerationContext context,
                                                       List<ImportItemExtractor.SourceItem> sourceItems,
                                                       String userPrompt) {
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;
        boolean localOllamaRequest = isLocalOllamaRequest(params);
        Integer totalTokensIn = null;
        Integer totalTokensOut = null;
        String responseModel = null;
        SourceDraftValidation lastValidation = null;
        List<UsageEvent> usageEvents = new ArrayList<>();

        for (int attempt = 0; attempt < GENERATE_MAX_ATTEMPTS; attempt++) {
            String prompt = buildCardsPrompt(
                    appendSourceValidationHint(userPrompt, lastValidation),
                    context.template(),
                    context.publicDeck(),
                    context.allowedFields(),
                    sourceItems.size(),
                    context.fewShotExamples(),
                    localOllamaRequest
            );
            JsonNode responseFormat = buildCardsSchema(context.allowedFields(), sourceItems.size(), true);
            long startedAtNs = System.nanoTime();
            OpenAiResponseResult response = openAiClient.createResponse(
                    apiKey,
                    new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
            );
            long durationMs = elapsedMillis(startedAtNs);
            responseModel = response.model();
            totalTokensIn = sumNullable(totalTokensIn, response.inputTokens());
            totalTokensOut = sumNullable(totalTokensOut, response.outputTokens());
            usageEvents.add(buildUsageEvent(
                    STEP_GENERATE_CONTENT,
                    attempt,
                    sourceItems.size(),
                    sourceItems.size(),
                    response,
                    durationMs
            ));

            JsonNode parsed = parseJsonResponse(response.outputText());
            List<CardDraft> drafts = buildCardDrafts(parsed, context.allowedFields(), context.template(), context.fieldTypes(), true);
            SourceDraftValidation validation = validateSourceDrafts(drafts, sourceItems, context.allowedFields());
            if (validation.valid()) {
                return new GeneratedDraftBatch(
                        responseModel,
                        totalTokensIn,
                        totalTokensOut,
                        validation.orderedDrafts(),
                        0,
                        0,
                        0,
                        0,
                        new SourceCoverage(sourceItems.size(), validation.orderedDrafts().size(), List.of(), 0, List.of()),
                        List.copyOf(usageEvents)
                );
            }
            lastValidation = validation;
        }

        throw new IllegalStateException("AI response failed source coverage validation: " + summarizeSourceValidation(lastValidation));
    }

    private AiJobProcessingResult applyGeneratedDrafts(AiJobEntity job,
                                                       String apiKey,
                                                       JsonNode params,
                                                       GenerationContext context,
                                                       GeneratedDraftBatch generated,
                                                       int requestedCount,
                                                       String accessToken) {
        ImageConfig imageConfig = resolveImageConfig(params, true);
        VideoConfig videoConfig = resolveVideoConfig(params, true);
        DraftMediaPreparationResult draftMediaResult = runStep(job, params, STEP_GENERATE_MEDIA, () -> prepareDraftMedia(
                job,
                apiKey,
                context.template(),
                generated.drafts(),
                context.fieldTypes(),
                imageConfig,
                videoConfig
        ));
        DraftTtsPreparationResult draftTtsResult = runStep(job, params, STEP_GENERATE_AUDIO, () -> applyTtsToDrafts(
                job,
                apiKey,
                params,
                generated.drafts(),
                context.template()
        ));
        List<CreateCardRequestPayload> requests = generated.drafts().stream()
                .map(draft -> new CreateCardRequestPayload(draft.content(), null, null, null, null, null))
                .toList();
        List<CoreUserCardResponse> createdCards = runStep(job, params, STEP_APPLY_CHANGES, () -> coreApiClient.addCards(
                job.getDeckId(),
                requests,
                accessToken,
                job.getJobId()
        ));
        MediaApplyResult mediaResult = materializeDraftMediaResult(createdCards, draftMediaResult);
        TtsApplyResult ttsResult = materializeDraftTtsResult(createdCards, draftTtsResult);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("templateId", context.publicDeck().templateId().toString());
        summary.put("requestedCards", requestedCount);
        summary.put("createdCards", requests.size());
        summary.put("duplicatesSkippedExact", generated.droppedExact());
        summary.put("duplicatesSkippedPrimary", generated.droppedPrimary());
        summary.put("duplicatesSkippedSemantic", generated.droppedSemantic());
        if (generated.droppedEmpty() > 0) {
            summary.put("candidatesSkippedEmpty", generated.droppedEmpty());
        }
        if (ttsResult.generated() > 0) {
            summary.put("ttsGenerated", ttsResult.generated());
        }
        if (ttsResult.charsGenerated() > 0) {
            summary.put("ttsCharsGenerated", ttsResult.charsGenerated());
        }
        if (ttsResult.error() != null) {
            summary.put("ttsError", ttsResult.error());
        }
        if (mediaResult.imagesGenerated() > 0) {
            summary.put("imagesGenerated", mediaResult.imagesGenerated());
        }
        if (mediaResult.videosGenerated() > 0) {
            summary.put("videosGenerated", mediaResult.videosGenerated());
        }
        if (generated.sourceCoverage() != null) {
            summary.set("sourceCoverage", buildSourceCoverageNode(generated.sourceCoverage()));
        }
        ObjectNode usageDetails = buildGenerationUsageDetails(generated, ttsResult, mediaResult);
        if (!usageDetails.isEmpty()) {
            summary.set("usage", usageDetails);
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        for (String field : context.allowedFields()) {
            fieldsNode.add(field);
        }
        summary.set("items", buildGeneratedCardItems(createdCards, generated.drafts(), context.allowedFields(), ttsResult, mediaResult));

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                generated.model(),
                generated.inputTokens(),
                generated.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash(),
                resolveFinalStatus(ttsResult.error() != null
                        || !ttsResult.cardErrors().isEmpty()
                        || !mediaResult.cardErrors().isEmpty()),
                usageDetails.isEmpty() ? null : usageDetails
        );
    }

    private Integer sumNullable(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    private ObjectNode buildSourceCoverageNode(SourceCoverage coverage) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sourceItemsTotal", coverage.sourceItemsTotal());
        node.put("sourceItemsUsed", coverage.sourceItemsUsed());
        node.put("alteredSourceItems", coverage.alteredSourceItems());
        ArrayNode missing = node.putArray("missingSourceIndexes");
        if (coverage.missingSourceIndexes() != null) {
            coverage.missingSourceIndexes().forEach(missing::add);
        }
        ArrayNode missingNumbers = node.putArray("missingNumberedItems");
        if (coverage.missingNumberedItems() != null) {
            coverage.missingNumberedItems().forEach(missingNumbers::add);
        }
        return node;
    }

    private ObjectNode buildGenerationUsageDetails(GeneratedDraftBatch generated,
                                                   TtsApplyResult ttsResult,
                                                   MediaApplyResult mediaResult) {
        ObjectNode details = objectMapper.createObjectNode();
        if (generated != null) {
            ObjectNode textNode = details.putObject("textGeneration");
            textNode.put("inputTokens", nullToZero(generated.inputTokens()));
            textNode.put("outputTokens", nullToZero(generated.outputTokens()));
            textNode.put("requests", generated.usageEvents() == null ? 0 : generated.usageEvents().size());
            ArrayNode calls = textNode.putArray("calls");
            if (generated.usageEvents() != null) {
                for (UsageEvent event : generated.usageEvents()) {
                    calls.add(buildUsageEventNode(event));
                }
            }
        }
        if (ttsResult != null && ttsResult.generated() > 0) {
            ObjectNode ttsNode = details.putObject("tts");
            ttsNode.put("requests", ttsResult.generated());
            ttsNode.put("charsGenerated", ttsResult.charsGenerated());
            if (ttsResult.model() != null) {
                ttsNode.put("model", ttsResult.model());
            }
        }
        if (mediaResult != null && (mediaResult.imagesGenerated() > 0 || mediaResult.videosGenerated() > 0)) {
            ObjectNode mediaNode = details.putObject("media");
            mediaNode.put("imagesGenerated", mediaResult.imagesGenerated());
            mediaNode.put("videosGenerated", mediaResult.videosGenerated());
        }
        return details;
    }

    private ObjectNode buildUsageEventNode(UsageEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stage", event.stage());
        node.put("attempt", event.attempt());
        node.put("requestedCount", event.requestedCount());
        node.put("candidateCount", event.candidateCount());
        if (event.model() != null) {
            node.put("model", event.model());
        }
        node.put("inputTokens", nullToZero(event.inputTokens()));
        node.put("outputTokens", nullToZero(event.outputTokens()));
        node.put("cachedInputTokens", nullToZero(event.cachedInputTokens()));
        node.put("reasoningOutputTokens", nullToZero(event.reasoningOutputTokens()));
        node.put("durationMs", event.durationMs());
        return node;
    }

    private UsageEvent buildUsageEvent(String stage,
                                       int attempt,
                                       int requestedCount,
                                       int candidateCount,
                                       OpenAiResponseResult response,
                                       long durationMs) {
        JsonNode usage = response == null || response.raw() == null
                ? null
                : response.raw().path("usage");
        return new UsageEvent(
                stage,
                attempt,
                requestedCount,
                candidateCount,
                response == null ? null : response.model(),
                response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(),
                readNestedInt(usage, "input_tokens_details", "cached_tokens"),
                readNestedInt(usage, "output_tokens_details", "reasoning_tokens"),
                durationMs
        );
    }

    private Integer readNestedInt(JsonNode node, String objectField, String intField) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(objectField).path(intField);
        return value.canConvertToInt() ? value.asInt() : null;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private long elapsedMillis(long startedAtNs) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs));
    }

    private AiJobProcessingResult handleTts(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String text = extractTextParam(params, "text", "input", "prompt");
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("TTS text is required");
        }
        String model = textOrDefault(params.path("model"), props.defaultTtsModel());
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("TTS model is required");
        }
        String voice = textOrNull(params.path("voice"));
        if (voice == null && props.defaultVoice() != null && !props.defaultVoice().isBlank()) {
            voice = props.defaultVoice().trim();
        }
        String format = textOrDefault(params.path("format"), props.defaultTtsFormat());
        if (format == null || format.isBlank()) {
            format = "mp3";
        }

        byte[] audio = createSpeechWithRetry(
                job,
                apiKey,
                new OpenAiSpeechRequest(model, text, voice, format),
                null,
                null
        );

        String contentType = resolveAudioContentType(format);
        String fileName = "ai-tts-" + job.getJobId() + "." + format;
        UUID mediaId = mediaApiClient.directUpload(
                job.getUserId(),
                "card_audio",
                contentType,
                fileName,
                audio.length,
                new ByteArrayInputStream(audio)
        );

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mediaId", mediaId.toString());
        summary.put("contentType", contentType);
        summary.put("fileName", fileName);
        summary.put("model", model);
        if (voice != null && !voice.isBlank()) {
            summary.put("voice", voice);
        }

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                model,
                null,
                null,
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private CredentialSelection resolveCredential(AiJobEntity job) {
        JsonNode params = safeParams(job);
        if (isLocalOllamaRequest(params)) {
            // Local self-host runs through the gateway without bearer auth. Sending any OpenAI-style
            // credential would route the request to the remote OpenAI backend instead of Ollama/local gateways.
            return new CredentialSelection(null, "");
        }
        UUID credentialId = parseUuid(params.path("providerCredentialId").asText(null));
        if (credentialId != null) {
            Optional<AiProviderCredentialEntity> byId = credentialRepository.findByIdAndUserId(credentialId, job.getUserId());
            if (byId.isPresent()) {
                AiProviderCredentialEntity credential = byId.get();
                return new CredentialSelection(credential, decryptSecret(credential));
            }
        }
        Optional<AiProviderCredentialEntity> credential = credentialRepository
                .findFirstByUserIdAndProviderAndStatusOrderByCreatedAtAsc(
                        job.getUserId(),
                        PROVIDER,
                        AiProviderStatus.active
                );
        if (credential.isPresent()) {
            AiProviderCredentialEntity entity = credential.get();
            return new CredentialSelection(entity, decryptSecret(entity));
        }
        return new CredentialSelection(null, systemApiKey);
    }

    private String decryptSecret(AiProviderCredentialEntity credential) {
        EncryptedSecret encrypted = new EncryptedSecret(
                credential.getEncryptedSecret(),
                credential.getEncryptedDataKey(),
                credential.getKeyId(),
                credential.getNonce(),
                credential.getAad()
        );
        byte[] raw = secretVault.decrypt(encrypted);
        return new String(raw, StandardCharsets.UTF_8).trim();
    }

    private JsonNode safeParams(AiJobEntity job) {
        return job.getParamsJson() == null ? objectMapper.createObjectNode() : job.getParamsJson();
    }

    private String extractTextParam(JsonNode params, String... keys) {
        for (String key : keys) {
            JsonNode value = params.get(key);
            if (value != null && value.isTextual()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node != null && node.isTextual()) {
            String value = node.asText();
            if (!value.isBlank()) {
                return value;
            }
        }
        return Objects.requireNonNullElse(fallback, "");
    }

    private String textOrNull(JsonNode node) {
        if (node != null && node.isTextual()) {
            String value = node.asText().trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private String resolveUpdateScope(AiJobEntity job,
                                      CoreUserDeckResponse deck,
                                      CorePublicDeckResponse publicDeck,
                                      JsonNode params) {
        String requested = textOrNull(params.path("updateScope"));
        if (requested == null) {
            requested = textOrNull(params.path("scope"));
        }
        if (requested != null) {
            String normalized = requested.trim().toLowerCase(Locale.ROOT);
            if ("global".equals(normalized)) {
                return "global";
            }
            if ("local".equals(normalized)) {
                return "local";
            }
            return "local";
        }
        return "local";
    }

    private String resolveCardUpdateScope(String updateScope, CoreUserCardResponse card) {
        if (updateScope == null || !updateScope.equalsIgnoreCase("global")) {
            return updateScope;
        }
        if (card == null || card.publicCardId() == null) {
            return "local";
        }
        return "global";
    }

    private String resolveCardUpdateScope(String updateScope, CoreApiClient.CoreUserCardDetail card) {
        if (updateScope == null || !updateScope.equalsIgnoreCase("global")) {
            return updateScope;
        }
        if (card == null || card.publicCardId() == null) {
            return "local";
        }
        return "global";
    }

    private UUID resolveUpdateOperationId(String updateScope, AiJobEntity job) {
        if (updateScope == null || !updateScope.equalsIgnoreCase("global")) {
            return null;
        }
        if (job == null) {
            return null;
        }
        return job.getJobId();
    }

    private int resolveCount(JsonNode params) {
        int count = params.path("count").isInt() ? params.path("count").asInt() : 10;
        if (count < 1) {
            count = 1;
        }
        int limit = params.path("countLimit").isInt() ? params.path("countLimit").asInt() : MAX_CARDS;
        if (limit < 1) {
            limit = 1;
        }
        return Math.min(count, limit);
    }

    private int resolveCandidateCount(int remaining, int attempt) {
        return resolveCandidateCount(remaining, attempt, false);
    }

    private int resolveCandidateCount(int remaining, int attempt, boolean localOllamaRequest) {
        int safeRemaining = Math.max(1, remaining);
        if (localOllamaRequest) {
            int extra = attempt == 0 ? 2 : 4;
            return Math.min(safeRemaining + extra, 12);
        }
        int boosted = attempt == 0
                ? Math.max(safeRemaining * 3, safeRemaining + 10)
                : Math.max(safeRemaining * 2, safeRemaining + 6);
        return Math.min(boosted, 120);
    }

    private String augmentGeneratePrompt(String userPrompt, CardNoveltyService.NoveltyIndex noveltyIndex, int attempt) {
        return augmentGeneratePrompt(userPrompt, noveltyIndex, attempt, false);
    }

    private String augmentGeneratePrompt(String userPrompt,
                                         CardNoveltyService.NoveltyIndex noveltyIndex,
                                         int attempt,
                                         boolean localOllamaRequest) {
        if (attempt <= 0) {
            return userPrompt;
        }
        int hintLimit = localOllamaRequest ? 8 : NOVELTY_HINT_LIMIT;
        List<String> snippets = noveltyService.buildAvoidSnippets(noveltyIndex, hintLimit);
        if (snippets.isEmpty()) {
            return userPrompt;
        }
        String forbidden = String.join(" | ", snippets);
        String suffix = "Avoid generating items semantically similar to these existing examples: " + forbidden;
        if (userPrompt == null || userPrompt.isBlank()) {
            return suffix;
        }
        return userPrompt.trim() + ". " + suffix;
    }

    private int resolveImportBatchSize(JsonNode params) {
        int fields = params.path("fields").isArray() ? params.path("fields").size() : 0;
        if (isLocalOllamaRequest(params)) {
            int localBatchSize = fields > 0 ? 24 / fields : LOCAL_IMPORT_MAX_BATCH;
            if (localBatchSize < LOCAL_IMPORT_MIN_BATCH) {
                localBatchSize = LOCAL_IMPORT_MIN_BATCH;
            }
            if (localBatchSize > LOCAL_IMPORT_MAX_BATCH) {
                localBatchSize = LOCAL_IMPORT_MAX_BATCH;
            }
            return localBatchSize;
        }
        int batchSize = fields > 0 ? 200 / fields : MAX_IMPORT_BATCH;
        if (batchSize < MIN_IMPORT_BATCH) {
            batchSize = MIN_IMPORT_BATCH;
        }
        if (batchSize > MAX_IMPORT_BATCH) {
            batchSize = MAX_IMPORT_BATCH;
        }
        return batchSize;
    }

    private AiJobProcessingResult handleGenerateCardsMaybeBatched(AiJobEntity job, String apiKey, JsonNode params) {
        int total = resolveCount(params);
        int batchSize = resolveLocalGenerateBatchSize(params);
        if (!isLocalOllamaRequest(params) || total <= batchSize) {
            return handleGenerateCards(job, apiKey, params);
        }

        ObjectNode batchedParams = params != null && params.isObject()
                ? ((ObjectNode) params).deepCopy()
                : objectMapper.createObjectNode();
        batchedParams.put("count", total);
        return handleGenerateCardsBatched(job, apiKey, batchedParams, total, batchSize);
    }

    private int resolveLocalGenerateBatchSize(JsonNode params) {
        int fields = params != null && params.path("fields").isArray() ? params.path("fields").size() : 0;
        int batchSize = fields > 0 ? 30 / fields : 6;
        return Math.max(4, Math.min(batchSize, 8));
    }

    private int resolveLimit(JsonNode node) {
        int limit = node != null && node.isInt() ? node.asInt() : 50;
        if (limit < 1) {
            limit = 1;
        }
        return Math.min(limit, 200);
    }

    private int resolveAuditSampleLimit(JsonNode node) {
        int limit = node != null && node.isInt() ? node.asInt() : 60;
        if (limit < 10) {
            limit = 10;
        }
        return Math.min(limit, 200);
    }

    private List<String> resolveAllowedFields(JsonNode params, CoreTemplateResponse template) {
        List<String> requested = extractRequestedFields(params);
        List<String> templateFields = template.fields() == null
                ? List.of()
                : template.fields().stream()
                    .filter(this::isPromptField)
                    .map(CoreFieldTemplate::name)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
        if (requested.isEmpty()) {
            return template.fields() == null
                    ? List.of()
                    : template.fields().stream()
                        .filter(this::isTextField)
                        .map(CoreFieldTemplate::name)
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .toList();
        }
        return requested.stream()
                .filter(templateFields::contains)
                .distinct()
                .toList();
    }

    private List<String> resolveAudioTargetFields(JsonNode params, List<String> audioFields) {
        List<String> requested = extractRequestedFields(params);
        if (requested.isEmpty()) {
            return audioFields;
        }
        return requested.stream()
                .filter(audioFields::contains)
                .distinct()
                .toList();
    }

    private List<String> extractRequestedFields(JsonNode params) {
        List<String> requested = extractStringArray(params.path("fields"));
        if (!requested.isEmpty()) {
            return requested;
        }
        return new ArrayList<>(extractFieldLimits(params).keySet());
    }

    private Map<String, Integer> extractFieldLimits(JsonNode params) {
        JsonNode limitsNode = params.path("fieldLimits");
        if (!limitsNode.isArray()) {
            return Map.of();
        }
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (JsonNode item : limitsNode) {
            if (!item.isObject()) {
                continue;
            }
            String field = item.path("field").asText(null);
            if (field == null || field.isBlank()) {
                continue;
            }
            int limit = item.path("limit").isInt() ? item.path("limit").asInt() : 0;
            if (limit <= 0) {
                continue;
            }
            limits.put(field.trim(), Math.min(limit, 200));
        }
        return limits;
    }

    private boolean isTextField(CoreFieldTemplate field) {
        if (field == null || field.fieldType() == null) {
            return false;
        }
        return switch (field.fieldType()) {
            case "text", "rich_text", "markdown", "cloze" -> true;
            default -> false;
        };
    }

    private boolean isPromptField(CoreFieldTemplate field) {
        if (field == null || field.fieldType() == null) {
            return false;
        }
        return switch (field.fieldType()) {
            case "text", "rich_text", "markdown", "cloze", "image", "video" -> true;
            default -> false;
        };
    }

    private List<String> extractStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                String value = item.asText().trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private boolean hasAction(JsonNode params, String action) {
        JsonNode node = params.path("actions");
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && action.equalsIgnoreCase(item.asText())) {
                return true;
            }
        }
        return false;
    }

    static String resolveEnhanceMode(JsonNode params) {
        if (hasAnyAction(params, "missing_fields", "fill_missing", "fields", "text", "image", "video", "all")) {
            return MODE_MISSING_FIELDS;
        }
        if (hasAnyAction(params, "missing_audio", "audio", "tts")) {
            return MODE_MISSING_AUDIO;
        }
        if (hasAnyAction(params, "audit", "analyze", "analysis")) {
            return MODE_AUDIT;
        }
        return MODE_MISSING_FIELDS;
    }

    private static boolean hasAnyAction(JsonNode params, String... actions) {
        JsonNode node = params.path("actions");
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText();
            for (String action : actions) {
                if (action.equalsIgnoreCase(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildAuditPrompt(JsonNode params,
                                    CoreTemplateResponse template,
                                    CorePublicDeckResponse publicDeck,
                                    List<String> fields,
                                    List<CoreUserCardResponse> cards,
                                    AuditAnalyzer.AuditContext analysis) {
        StringBuilder builder = new StringBuilder();
        builder.append("Audit this deck for quality issues and inconsistencies. ");
        builder.append("Return JSON with keys: summary, issues, recommendations, nextActions. ");
        builder.append("Be specific, concise, and reference field names. ");
        builder.append("You are given a sample of cards; do not assume the total deck size. ");

        String deckName = publicDeck == null ? null : publicDeck.name();
        String deckDescription = publicDeck == null ? null : publicDeck.description();
        String deckLanguage = publicDeck == null ? null : publicDeck.language();
        if (deckName != null && !deckName.isBlank()) {
            builder.append("Deck name: ").append(deckName.trim()).append(". ");
        }
        if (deckDescription != null && !deckDescription.isBlank()) {
            builder.append("Deck description: ").append(deckDescription.trim()).append(". ");
        }
        if (deckLanguage != null && !deckLanguage.isBlank()) {
            builder.append("Deck language: ").append(deckLanguage.trim()).append(". ");
        }
        if (template != null) {
            String templateName = template.name();
            String templateDescription = template.description();
            if (templateName != null && !templateName.isBlank()) {
                builder.append("Template name: ").append(templateName.trim()).append(". ");
            }
            if (templateDescription != null && !templateDescription.isBlank()) {
                builder.append("Template description: ").append(templateDescription.trim()).append(". ");
            }
            String fieldHints = buildFieldHints(template, fields);
            if (!fieldHints.isBlank()) {
                builder.append("Field hints: ").append(fieldHints).append(". ");
            }
            String profile = formatAiProfile(template.aiProfile());
            if (!profile.isBlank()) {
                builder.append("Template AI profile: ").append(profile).append(". ");
            }
        }
        builder.append("Audit stats JSON: ").append(analysis.summary().toString()).append(". ");
        builder.append("Detected issues JSON: ").append(analysis.issues().toString()).append(". ");

        builder.append("Sample cards:\n");
        int shown = 0;
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            builder.append("- id: ").append(card.userCardId()).append("\n");
            builder.append("  fields: ").append(card.effectiveContent().toString()).append("\n");
            shown++;
            if (shown >= 30) {
                break;
            }
        }

        String userPrompt = extractTextParam(params, "input", "prompt", "notes");
        if (userPrompt != null && !userPrompt.isBlank()) {
            builder.append("User instructions: ").append(userPrompt.trim());
        }
        return builder.toString().trim();
    }

    private ObjectNode buildAuditResponseFormat() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("summary").put("type", "string");
        ObjectNode issuesNode = properties.putObject("issues");
        issuesNode.put("type", "array");
        issuesNode.putObject("items").put("type", "string");
        ObjectNode recommendationsNode = properties.putObject("recommendations");
        recommendationsNode.put("type", "array");
        recommendationsNode.putObject("items").put("type", "string");
        ObjectNode nextActionsNode = properties.putObject("nextActions");
        nextActionsNode.put("type", "array");
        nextActionsNode.putObject("items").put("type", "string");
        schema.putArray("required").add("summary").add("issues").add("recommendations").add("nextActions");

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "mnema_audit");
        responseFormat.set("schema", schema);
        responseFormat.put("strict", true);
        return responseFormat;
    }

    private ObjectNode buildCardAuditResponseFormat() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("summary").put("type", "string");
        ObjectNode itemsNode = properties.putObject("items");
        itemsNode.put("type", "array");
        ObjectNode itemSchema = itemsNode.putObject("items");
        itemSchema.put("type", "object");
        ObjectNode itemProps = itemSchema.putObject("properties");
        itemProps.putObject("field").put("type", "string");
        itemProps.putObject("message").put("type", "string");
        itemProps.putObject("suggestion").put("type", "string");
        itemProps.putObject("severity").put("type", "string");
        itemSchema.put("additionalProperties", false);
        itemSchema.putArray("required").add("field").add("message").add("suggestion").add("severity");
        schema.putArray("required").add("summary").add("items");

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "mnema_card_audit");
        responseFormat.set("schema", schema);
        responseFormat.put("strict", true);
        return responseFormat;
    }

    private String buildMissingFieldsPrompt(String userPrompt,
                                            CoreTemplateResponse template,
                                            CorePublicDeckResponse publicDeck,
                                            List<String> targetFields,
                                            List<CoreUserCardResponse> cards,
                                            UUID deckId,
                                            String accessToken) {
        StringBuilder builder = new StringBuilder();
        builder.append("You will fill missing fields for existing flashcards. ");
        builder.append("Return JSON with an 'updates' array. Each update must include 'userCardId' and 'fields'. ");
        builder.append("Only include fields from this list: ").append(String.join(", ", targetFields)).append(". ");
        builder.append("Do not modify fields that already have values. If unsure, leave the field empty. ");
        if (hasFieldType(template, targetFields, "image") || hasFieldType(template, targetFields, "video")) {
            builder.append("For image or video fields, return a short visual prompt describing the scene. ");
            builder.append("Do not include URLs, markdown, or file names. ");
            if (hasFieldType(template, targetFields, "video")) {
                builder.append("For video fields, describe a short 3-6 second clip. ");
            }
        }

        String deckName = publicDeck == null ? null : publicDeck.name();
        String deckDescription = publicDeck == null ? null : publicDeck.description();
        String deckLanguage = publicDeck == null ? null : publicDeck.language();
        if (deckName != null && !deckName.isBlank()) {
            builder.append("Deck name: ").append(deckName.trim()).append(". ");
        }
        if (deckDescription != null && !deckDescription.isBlank()) {
            builder.append("Deck description: ").append(deckDescription.trim()).append(". ");
        }
        if (deckLanguage != null && !deckLanguage.isBlank()) {
            builder.append("Deck language: ").append(deckLanguage.trim()).append(". ");
        }

        if (template != null) {
            String templateName = template.name();
            String templateDescription = template.description();
            if (templateName != null && !templateName.isBlank()) {
                builder.append("Template name: ").append(templateName.trim()).append(". ");
            }
            if (templateDescription != null && !templateDescription.isBlank()) {
                builder.append("Template description: ").append(templateDescription.trim()).append(". ");
            }
            String fieldHints = buildFieldHints(template, targetFields);
            if (!fieldHints.isBlank()) {
                builder.append("Field hints: ").append(fieldHints).append(". ");
            }
            String profile = formatAiProfile(template.aiProfile());
            if (!profile.isBlank()) {
                builder.append("Template AI profile: ").append(profile).append(". ");
            }
        }

        String examples = buildFewShotExamples(deckId, accessToken, targetFields);
        if (!examples.isBlank()) {
            builder.append("Examples of completed fields: ").append(examples).append(". ");
        }

        builder.append("Cards:\n");
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            builder.append("- id: ").append(card.userCardId()).append("\n");
            builder.append("  fields: ").append(card.effectiveContent().toString()).append("\n");
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            builder.append("User instructions: ").append(userPrompt.trim());
        }
        return builder.toString().trim();
    }

    private ObjectNode buildMissingFieldsResponseFormat(List<String> targetFields) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode updates = properties.putObject("updates");
        updates.put("type", "array");
        ObjectNode items = updates.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("userCardId").put("type", "string");
        ObjectNode fieldsNode = itemProps.putObject("fields");
        fieldsNode.put("type", "object");
        ObjectNode fieldProps = fieldsNode.putObject("properties");
        for (String field : targetFields) {
            fieldProps.putObject(field).put("type", "string");
        }
        fieldsNode.put("additionalProperties", false);
        ArrayNode fieldsRequired = fieldsNode.putArray("required");
        for (String field : targetFields) {
            fieldsRequired.add(field);
        }
        items.putArray("required").add("userCardId").add("fields");
        items.put("additionalProperties", false);
        schema.put("additionalProperties", false);
        schema.putArray("required").add("updates");

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "mnema_missing_fields");
        responseFormat.set("schema", schema);
        responseFormat.put("strict", true);
        return responseFormat;
    }

    private ObjectNode buildCardMissingFieldsResponseFormat(List<String> targetFields) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        for (String field : targetFields) {
            properties.putObject(field).put("type", "string");
        }
        ArrayNode required = schema.putArray("required");
        for (String field : targetFields) {
            required.add(field);
        }

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "mnema_card_missing_fields");
        responseFormat.set("schema", schema);
        responseFormat.put("strict", true);
        return responseFormat;
    }

    private String buildCardAuditPrompt(CorePublicDeckResponse publicDeck,
                                        CoreTemplateResponse template,
                                        CoreApiClient.CoreUserCardDetail card) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are reviewing a single flashcard. Provide improvements only if they make the card clearer or more accurate. ");
        builder.append("Ignore any template quirks if the card content is solid. ");
        builder.append("Focus on clarity, correctness, examples, and language quality. ");
        builder.append("Return JSON with 'summary' and an 'items' array. Each item should contain 'field', 'message', 'suggestion', and 'severity' (low, medium, or high). ");

        if (publicDeck != null) {
            if (publicDeck.name() != null && !publicDeck.name().isBlank()) {
                builder.append("Deck name: ").append(publicDeck.name().trim()).append(". ");
            }
            if (publicDeck.description() != null && !publicDeck.description().isBlank()) {
                builder.append("Deck description: ").append(publicDeck.description().trim()).append(". ");
            }
            if (publicDeck.language() != null && !publicDeck.language().isBlank()) {
                builder.append("Deck language: ").append(publicDeck.language().trim()).append(". ");
            }
        }

        if (template != null) {
            if (template.name() != null && !template.name().isBlank()) {
                builder.append("Template name: ").append(template.name().trim()).append(". ");
            }
            if (template.description() != null && !template.description().isBlank()) {
                builder.append("Template description: ").append(template.description().trim()).append(". ");
            }
            String profile = formatAiProfile(template.aiProfile());
            if (!profile.isBlank()) {
                builder.append("Template AI profile: ").append(profile).append(". ");
            }
            builder.append("Template fields: ").append(formatTemplateFields(template)).append(". ");
        }

        if (card.tags() != null && card.tags().length > 0) {
            builder.append("Card tags: ").append(String.join(", ", card.tags())).append(". ");
        }
        if (card.effectiveContent() != null) {
            builder.append("Card content: ").append(card.effectiveContent().toString()).append(". ");
        }

        return builder.toString().trim();
    }

    private String buildCardMissingFieldsPrompt(CorePublicDeckResponse publicDeck,
                                                CoreTemplateResponse template,
                                                CoreApiClient.CoreUserCardDetail card,
                                                List<String> targetFields) {
        StringBuilder builder = new StringBuilder();
        builder.append("Fill missing fields for a single flashcard. ");
        builder.append("Return JSON with only the fields that are missing. ");
        builder.append("Only include fields from this list: ").append(String.join(", ", targetFields)).append(". ");
        builder.append("Do not modify fields that already have values. ");
        if (hasFieldType(template, targetFields, "image") || hasFieldType(template, targetFields, "video")) {
            builder.append("For image or video fields, return a short visual prompt describing the scene. ");
            builder.append("Do not include URLs, markdown, or file names. ");
            if (hasFieldType(template, targetFields, "video")) {
                builder.append("For video fields, describe a short 3-6 second clip. ");
            }
        }

        if (publicDeck != null) {
            if (publicDeck.name() != null && !publicDeck.name().isBlank()) {
                builder.append("Deck name: ").append(publicDeck.name().trim()).append(". ");
            }
            if (publicDeck.description() != null && !publicDeck.description().isBlank()) {
                builder.append("Deck description: ").append(publicDeck.description().trim()).append(". ");
            }
            if (publicDeck.language() != null && !publicDeck.language().isBlank()) {
                builder.append("Deck language: ").append(publicDeck.language().trim()).append(". ");
            }
        }

        if (template != null) {
            String fieldHints = buildFieldHints(template, targetFields);
            if (!fieldHints.isBlank()) {
                builder.append("Field hints: ").append(fieldHints).append(". ");
            }
            String profile = formatAiProfile(template.aiProfile());
            if (!profile.isBlank()) {
                builder.append("Template AI profile: ").append(profile).append(". ");
            }
        }

        if (card.effectiveContent() != null) {
            builder.append("Card content: ").append(card.effectiveContent().toString()).append(". ");
        }

        return builder.toString().trim();
    }

    private String formatTemplateFields(CoreTemplateResponse template) {
        if (template == null || template.fields() == null || template.fields().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CoreFieldTemplate field : template.fields()) {
            if (field == null || field.name() == null) {
                continue;
            }
            builder.append(field.name());
            if (field.label() != null && !field.label().isBlank()) {
                builder.append(" (").append(field.label().trim()).append(")");
            }
            builder.append(": ").append(field.fieldType());
            if (field.isRequired()) {
                builder.append(", required");
            }
            if (field.isOnFront()) {
                builder.append(", front");
            }
            builder.append("; ");
        }
        return builder.toString().trim();
    }

    private MediaApplyResult applyCardMissingFieldUpdate(AiJobEntity job,
                                                         String apiKey,
                                                         String accessToken,
                                                         CoreTemplateResponse template,
                                                         CoreApiClient.CoreUserCardDetail card,
                                                         JsonNode response,
                                                         List<String> targetFields,
                                                         Map<String, String> fieldTypes,
                                                         ImageConfig imageConfig,
                                                         VideoConfig videoConfig,
                                                         String updateScope) {
        if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
            return new MediaApplyResult(0, 0, 0);
        }
        if (response == null || !response.isObject()) {
            return new MediaApplyResult(0, 0, 0);
        }
        ObjectNode updatedContent = card.effectiveContent().deepCopy();
        boolean changed = false;
        int imagesGenerated = 0;
        int videosGenerated = 0;
        Map<UUID, List<String>> cardErrors = new LinkedHashMap<>();
        var allowed = new java.util.HashSet<>(targetFields);
        var it = response.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            if (!allowed.contains(field) || value == null || !value.isTextual()) {
                continue;
            }
            String text = value.asText().trim();
            if (text.isEmpty()) {
                continue;
            }
            String fieldType = fieldTypes.get(field);
            if (fieldType == null || isTextFieldType(fieldType)) {
                if (isMissingText(updatedContent.get(field))) {
                    updatedContent.put(field, text);
                    changed = true;
                }
                continue;
            }
            if ("image".equals(fieldType)) {
                if (!imageConfig.enabled()) {
                    continue;
                }
                if (isMissingMedia(updatedContent.get(field))) {
                    try {
                        MediaUpload upload = generateImage(job, apiKey, imageConfig, text);
                        updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                        changed = true;
                        imagesGenerated++;
                    } catch (Exception ex) {
                        appendCardError(cardErrors, card.userCardId(), field, summarizeError(ex));
                        LOGGER.warn("OpenAI image generation failed jobId={} cardId={} field={} model={} promptLength={}",
                                job.getJobId(),
                                card.userCardId(),
                                field,
                                imageConfig.model(),
                                text.length(),
                                ex);
                    }
                }
                continue;
            }
            if ("video".equals(fieldType)) {
                if (!videoConfig.enabled()) {
                    continue;
                }
                if (isMissingMedia(updatedContent.get(field))) {
                    try {
                        MediaUpload upload = generateVideo(job, apiKey, videoConfig, text);
                        updatedContent.set(field, buildMediaNode(upload.mediaId(), "video"));
                        changed = true;
                        videosGenerated++;
                    } catch (Exception ex) {
                        appendCardError(cardErrors, card.userCardId(), field, summarizeError(ex));
                        LOGGER.warn("OpenAI video generation failed jobId={} cardId={} field={} model={} promptLength={}",
                                job.getJobId(),
                                card.userCardId(),
                                field,
                                videoConfig.model(),
                                text.length(),
                                ex);
                    }
                }
            }
        }
        if (!changed) {
            return new MediaApplyResult(0, imagesGenerated, videosGenerated, Set.of(), cardErrors);
        }
        ankiSupport.applyIfPresent(updatedContent, template);
        UpdateUserCardRequest updateRequest = new UpdateUserCardRequest(
                card.userCardId(),
                null,
                false,
                false,
                card.personalNote(),
                updatedContent
        );
        String cardScope = resolveCardUpdateScope(updateScope, card);
        UUID operationId = resolveUpdateOperationId(cardScope, job);
        coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), updateRequest, accessToken, cardScope, operationId);
        return new MediaApplyResult(1, imagesGenerated, videosGenerated, Set.of(card.userCardId()), cardErrors);
    }

    private AtomicApplyResult applyCardMissingFieldUpdateAtomically(AiJobEntity job,
                                                                    String apiKey,
                                                                    String accessToken,
                                                                    JsonNode params,
                                                                    CoreTemplateResponse template,
                                                                    CoreApiClient.CoreUserCardDetail card,
                                                                    JsonNode response,
                                                                    List<String> targetFields,
                                                                    Map<String, String> fieldTypes,
                                                                    ImageConfig imageConfig,
                                                                    VideoConfig videoConfig,
                                                                    List<String> targetAudioFields,
                                                                    String updateScope) {
        if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
            return new AtomicApplyResult(new MediaApplyResult(0, 0, 0), new TtsApplyResult(0, 0, null, null));
        }
        if (response == null || !response.isObject()) {
            return new AtomicApplyResult(new MediaApplyResult(0, 0, 0), new TtsApplyResult(0, 0, null, null));
        }
        ObjectNode updatedContent = loadLatestContent(job.getJobId(), job.getDeckId(), card.userCardId(), accessToken, card.effectiveContent().deepCopy());
        ContentMutationResult contentResult = applyMissingFieldsToContent(
                job,
                apiKey,
                updatedContent,
                response,
                fieldTypes,
                new LinkedHashSet<>(targetFields),
                imageConfig,
                videoConfig,
                card.userCardId().toString()
        );
        TtsContentApplyResult ttsContentResult = applyTtsToContent(
                job,
                apiKey,
                params.path("tts"),
                updatedContent,
                template,
                targetAudioFields,
                card.userCardId(),
                card.userCardId().toString()
        );
        Map<UUID, List<String>> mediaErrors = new LinkedHashMap<>();
        appendErrors(mediaErrors, card.userCardId(), contentResult.errors());
        Map<UUID, List<String>> ttsErrors = new LinkedHashMap<>();
        appendErrors(ttsErrors, card.userCardId(), ttsContentResult.errors());
        if (contentResult.changed() || ttsContentResult.updated()) {
            ankiSupport.applyIfPresent(updatedContent, template);
            UpdateUserCardRequest updateRequest = new UpdateUserCardRequest(
                    card.userCardId(),
                    null,
                    false,
                    false,
                    card.personalNote(),
                    updatedContent
            );
            String cardScope = resolveCardUpdateScope(updateScope, card);
            UUID operationId = resolveUpdateOperationId(cardScope, job);
            coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), updateRequest, accessToken, cardScope, operationId);
        }
        return new AtomicApplyResult(
                new MediaApplyResult(
                        contentResult.changed() ? 1 : 0,
                        contentResult.imagesGenerated(),
                        contentResult.videosGenerated(),
                        contentResult.changed() ? Set.of(card.userCardId()) : Set.of(),
                        mediaErrors
                ),
                new TtsApplyResult(
                        ttsContentResult.generated(),
                        ttsContentResult.updated() ? 1 : 0,
                        ttsContentResult.charsGenerated(),
                        ttsContentResult.model(),
                        ttsContentResult.errors().isEmpty() ? null : ttsContentResult.errors().getFirst(),
                        ttsContentResult.updated() ? Set.of(card.userCardId()) : Set.of(),
                        ttsErrors
                )
        );
    }

    private List<MissingFieldUpdate> parseMissingFieldUpdates(JsonNode response, List<String> allowedFields) {
        JsonNode updatesNode = response.path("updates");
        if (!updatesNode.isArray()) {
            throw new IllegalStateException("AI response missing updates array");
        }
        List<MissingFieldUpdate> updates = new java.util.ArrayList<>();
        var allowed = new java.util.HashSet<>(allowedFields);
        for (JsonNode updateNode : updatesNode) {
            if (!updateNode.isObject()) {
                continue;
            }
            UUID cardId = parseUuid(updateNode.path("userCardId").asText(null));
            if (cardId == null) {
                continue;
            }
            JsonNode fieldsNode = updateNode.path("fields");
            if (!fieldsNode.isObject()) {
                continue;
            }
            ObjectNode fields = objectMapper.createObjectNode();
            fieldsNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (allowed.contains(key) && value != null && value.isTextual()) {
                    String text = value.asText().trim();
                    if (!text.isEmpty()) {
                        fields.put(key, text);
                    }
                }
            });
            if (fields.isEmpty()) {
                continue;
            }
            updates.add(new MissingFieldUpdate(cardId, fields));
        }
        return updates;
    }

    private MediaApplyResult applyMissingFieldUpdates(AiJobEntity job,
                                                      String apiKey,
                                                      String accessToken,
                                                      CoreTemplateResponse template,
                                                      List<CoreUserCardResponse> cards,
                                                      List<MissingFieldUpdate> updates,
                                                      Map<String, String> fieldTypes,
                                                      Map<UUID, Set<String>> allowedFieldsByCard,
                                                      ImageConfig imageConfig,
                                                      VideoConfig videoConfig,
                                                      String updateScope) {
        if (updates.isEmpty()) {
            return new MediaApplyResult(0, 0, 0);
        }
        Map<UUID, CoreUserCardResponse> cardMap = cards.stream()
                .filter(card -> card != null && card.userCardId() != null)
                .collect(java.util.stream.Collectors.toMap(CoreUserCardResponse::userCardId, card -> card, (a, b) -> a));
        int updated = 0;
        int imagesGenerated = 0;
        int videosGenerated = 0;
        Set<UUID> updatedCardIds = new LinkedHashSet<>();
        Map<UUID, List<String>> cardErrors = new LinkedHashMap<>();
        for (MissingFieldUpdate update : updates) {
            CoreUserCardResponse card = cardMap.get(update.userCardId());
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = card.effectiveContent().deepCopy();
            boolean changed = false;
            Set<String> allowed = allowedFieldsByCard.get(update.userCardId());
            var it = update.fields().fields();
            while (it.hasNext()) {
                var entry = it.next();
                String field = entry.getKey();
                if (allowed != null && !allowed.isEmpty() && !allowed.contains(field)) {
                    continue;
                }
                String value = entry.getValue().asText();
                String fieldType = fieldTypes.get(field);
                if (fieldType == null || isTextFieldType(fieldType)) {
                    if (isMissingText(updatedContent.get(field))) {
                        updatedContent.put(field, value);
                        changed = true;
                    }
                    continue;
                }
                if ("image".equals(fieldType)) {
                    if (!imageConfig.enabled() || value == null || value.isBlank()) {
                        continue;
                    }
                    if (isMissingMedia(updatedContent.get(field))) {
                        try {
                            MediaUpload upload = generateImage(job, apiKey, imageConfig, value.trim());
                            updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                            changed = true;
                            imagesGenerated++;
                        } catch (Exception ex) {
                            appendCardError(cardErrors, update.userCardId(), field, summarizeError(ex));
                            LOGGER.warn("OpenAI image generation failed jobId={} cardId={} field={} model={} promptLength={}",
                                    job.getJobId(),
                                    update.userCardId(),
                                    field,
                                    imageConfig.model(),
                                    value.length(),
                                    ex);
                        }
                    }
                    continue;
                }
                if ("video".equals(fieldType)) {
                    if (!videoConfig.enabled() || value == null || value.isBlank()) {
                        continue;
                    }
                    if (isMissingMedia(updatedContent.get(field))) {
                        try {
                            MediaUpload upload = generateVideo(job, apiKey, videoConfig, value.trim());
                            updatedContent.set(field, buildMediaNode(upload.mediaId(), "video"));
                            changed = true;
                            videosGenerated++;
                        } catch (Exception ex) {
                            appendCardError(cardErrors, update.userCardId(), field, summarizeError(ex));
                            LOGGER.warn("OpenAI video generation failed jobId={} cardId={} field={} model={} promptLength={}",
                                    job.getJobId(),
                                    update.userCardId(),
                                    field,
                                    videoConfig.model(),
                                    value.length(),
                                    ex);
                        }
                    }
                }
            }
            if (!changed) {
                continue;
            }
            ankiSupport.applyIfPresent(updatedContent, template);
            UpdateUserCardRequest updateRequest = new UpdateUserCardRequest(
                    card.userCardId(),
                    null,
                    false,
                    false,
                    null,
                    updatedContent
            );
            String cardScope = resolveCardUpdateScope(updateScope, card);
            UUID operationId = resolveUpdateOperationId(cardScope, job);
            coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), updateRequest, accessToken, cardScope, operationId);
            updated++;
            updatedCardIds.add(card.userCardId());
        }
        return new MediaApplyResult(updated, imagesGenerated, videosGenerated, updatedCardIds, cardErrors);
    }

    private AtomicApplyResult applyMissingFieldUpdatesAtomically(AiJobEntity job,
                                                                 String apiKey,
                                                                 String accessToken,
                                                                 JsonNode params,
                                                                 CoreTemplateResponse template,
                                                                 List<CoreUserCardResponse> cards,
                                                                 List<MissingFieldUpdate> updates,
                                                                 Map<String, String> fieldTypes,
                                                                 Map<UUID, Set<String>> allowedFieldsByCard,
                                                                 ImageConfig imageConfig,
                                                                 VideoConfig videoConfig,
                                                                 List<String> targetAudioFields,
                                                                 String updateScope) {
        if (updates.isEmpty()) {
            return new AtomicApplyResult(new MediaApplyResult(0, 0, 0), new TtsApplyResult(0, 0, null, null));
        }
        Map<UUID, CoreUserCardResponse> cardMap = cards.stream()
                .filter(card -> card != null && card.userCardId() != null)
                .collect(java.util.stream.Collectors.toMap(CoreUserCardResponse::userCardId, card -> card, (a, b) -> a));
        int imagesGenerated = 0;
        int videosGenerated = 0;
        Set<UUID> mediaUpdatedCardIds = new LinkedHashSet<>();
        Map<UUID, List<String>> mediaCardErrors = new LinkedHashMap<>();
        int ttsGenerated = 0;
        int ttsCharsGenerated = 0;
        int ttsUpdatedCards = 0;
        String ttsModel = null;
        String ttsError = null;
        Set<UUID> ttsUpdatedCardIds = new LinkedHashSet<>();
        Map<UUID, List<String>> ttsCardErrors = new LinkedHashMap<>();
        for (MissingFieldUpdate update : updates) {
            CoreUserCardResponse card = cardMap.get(update.userCardId());
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = loadLatestContent(job.getJobId(), job.getDeckId(), card.userCardId(), accessToken, card.effectiveContent().deepCopy());
            ContentMutationResult contentResult = applyMissingFieldsToContent(
                    job,
                    apiKey,
                    updatedContent,
                    update.fields(),
                    fieldTypes,
                    allowedFieldsByCard.get(update.userCardId()),
                    imageConfig,
                    videoConfig,
                    update.userCardId().toString()
            );
            appendErrors(mediaCardErrors, update.userCardId(), contentResult.errors());
            TtsContentApplyResult ttsResult = applyTtsToContent(
                    job,
                    apiKey,
                    params.path("tts"),
                    updatedContent,
                    template,
                    targetAudioFields,
                    update.userCardId(),
                    update.userCardId().toString()
            );
            appendErrors(ttsCardErrors, update.userCardId(), ttsResult.errors());
            if (ttsModel == null) {
                ttsModel = ttsResult.model();
            }
            if (ttsError == null && !ttsResult.errors().isEmpty()) {
                ttsError = ttsResult.errors().getFirst();
            }
            boolean shouldUpdate = contentResult.changed() || ttsResult.updated();
            if (!shouldUpdate) {
                imagesGenerated += contentResult.imagesGenerated();
                videosGenerated += contentResult.videosGenerated();
                ttsGenerated += ttsResult.generated();
                ttsCharsGenerated += ttsResult.charsGenerated();
                continue;
            }
            ankiSupport.applyIfPresent(updatedContent, template);
            UpdateUserCardRequest updateRequest = new UpdateUserCardRequest(
                    card.userCardId(),
                    null,
                    false,
                    false,
                    null,
                    updatedContent
            );
            String cardScope = resolveCardUpdateScope(updateScope, card);
            UUID operationId = resolveUpdateOperationId(cardScope, job);
            coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), updateRequest, accessToken, cardScope, operationId);
            imagesGenerated += contentResult.imagesGenerated();
            videosGenerated += contentResult.videosGenerated();
            ttsGenerated += ttsResult.generated();
            ttsCharsGenerated += ttsResult.charsGenerated();
            if (contentResult.changed()) {
                mediaUpdatedCardIds.add(card.userCardId());
            }
            if (ttsResult.updated()) {
                ttsUpdatedCards++;
                ttsUpdatedCardIds.add(card.userCardId());
            }
        }
        return new AtomicApplyResult(
                new MediaApplyResult(mediaUpdatedCardIds.size(), imagesGenerated, videosGenerated, mediaUpdatedCardIds, mediaCardErrors),
                new TtsApplyResult(ttsGenerated, ttsUpdatedCards, ttsCharsGenerated, ttsModel, ttsError, ttsUpdatedCardIds, ttsCardErrors)
        );
    }

    private boolean isMissingText(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().trim().isEmpty();
        }
        return false;
    }

    private ContentMutationResult applyMissingFieldsToContent(AiJobEntity job,
                                                              String apiKey,
                                                              ObjectNode updatedContent,
                                                              JsonNode fieldsNode,
                                                              Map<String, String> fieldTypes,
                                                              Set<String> allowedFields,
                                                              ImageConfig imageConfig,
                                                              VideoConfig videoConfig,
                                                              String contentToken) {
        if (updatedContent == null || fieldsNode == null || !fieldsNode.isObject()) {
            return new ContentMutationResult(false, 0, 0, List.of());
        }
        boolean changed = false;
        int imagesGenerated = 0;
        int videosGenerated = 0;
        List<String> errors = new ArrayList<>();
        var it = fieldsNode.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            if (allowedFields != null && !allowedFields.isEmpty() && !allowedFields.contains(field)) {
                continue;
            }
            if (value == null || !value.isTextual()) {
                continue;
            }
            String text = value.asText().trim();
            if (text.isEmpty()) {
                continue;
            }
            String fieldType = fieldTypes.get(field);
            if (fieldType == null || isTextFieldType(fieldType)) {
                if (isMissingText(updatedContent.get(field))) {
                    updatedContent.put(field, text);
                    changed = true;
                }
                continue;
            }
            if ("image".equals(fieldType)) {
                if (!imageConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                    continue;
                }
                try {
                    MediaUpload upload = generateImage(job, apiKey, imageConfig, text);
                    updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                    changed = true;
                    imagesGenerated++;
                } catch (Exception ex) {
                    errors.add(formatFieldError(field, summarizeError(ex)));
                    LOGGER.warn("OpenAI image generation failed jobId={} content={} field={} model={} promptLength={}",
                            job.getJobId(),
                            contentToken,
                            field,
                            imageConfig.model(),
                            text.length(),
                            ex);
                }
                continue;
            }
            if ("video".equals(fieldType)) {
                if (!videoConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                    continue;
                }
                try {
                    MediaUpload upload = generateVideo(job, apiKey, videoConfig, text);
                    updatedContent.set(field, buildMediaNode(upload.mediaId(), "video"));
                    changed = true;
                    videosGenerated++;
                } catch (Exception ex) {
                    errors.add(formatFieldError(field, summarizeError(ex)));
                    LOGGER.warn("OpenAI video generation failed jobId={} content={} field={} model={} promptLength={}",
                            job.getJobId(),
                            contentToken,
                            field,
                            videoConfig.model(),
                            text.length(),
                            ex);
                }
            }
        }
        return new ContentMutationResult(changed, imagesGenerated, videosGenerated, errors);
    }

    private boolean isMissingAudio(JsonNode node) {
        return isMissingMedia(node);
    }

    private record MissingFieldUpdate(UUID userCardId, ObjectNode fields) {
    }

    private record CardDraft(ObjectNode content,
                             Map<String, String> mediaPrompts,
                             Integer sourceIndex,
                             String sourceText) {
    }

    private record ContentMutationResult(boolean changed,
                                         int imagesGenerated,
                                         int videosGenerated,
                                         List<String> errors) {
    }

    private record TtsApplyResult(int generated,
                                  int updatedCards,
                                  int charsGenerated,
                                  String model,
                                  String error,
                                  Set<UUID> updatedCardIds,
                                  Map<UUID, List<String>> cardErrors) {
        private TtsApplyResult(int generated, int updatedCards, String model, String error) {
            this(generated, updatedCards, 0, model, error, Set.of(), Map.of());
        }

        private TtsApplyResult(int generated, int updatedCards, int charsGenerated, String model, String error) {
            this(generated, updatedCards, charsGenerated, model, error, Set.of(), Map.of());
        }
    }

    private record MediaApplyResult(int updatedCards,
                                    int imagesGenerated,
                                    int videosGenerated,
                                    Set<UUID> updatedCardIds,
                                    Map<UUID, List<String>> cardErrors) {
        private MediaApplyResult(int updatedCards, int imagesGenerated, int videosGenerated) {
            this(updatedCards, imagesGenerated, videosGenerated, Set.of(), Map.of());
        }
    }

    private record TtsContentApplyResult(boolean updated,
                                         int generated,
                                         int charsGenerated,
                                         String model,
                                         List<String> errors) {
    }

    private record DraftMediaPreparationResult(int imagesGenerated,
                                               int videosGenerated,
                                               Set<Integer> updatedDraftIndexes,
                                               Map<Integer, List<String>> draftErrors) {
    }

    private record DraftTtsPreparationResult(int generated,
                                             int charsGenerated,
                                             String model,
                                             String error,
                                             Set<Integer> updatedDraftIndexes,
                                             Map<Integer, List<String>> draftErrors) {
    }

    private record AtomicApplyResult(MediaApplyResult mediaResult,
                                     TtsApplyResult ttsResult) {
    }

    private record GenerationContext(CoreUserDeckResponse deck,
                                     CorePublicDeckResponse publicDeck,
                                     CoreTemplateResponse template,
                                     String updateScope,
                                     int count,
                                     List<String> allowedFields,
                                     Map<String, String> fieldTypes,
                                     CardNoveltyService.NoveltyIndex noveltyIndex,
                                     String fewShotExamples) {
    }

    private record GeneratedDraftBatch(String model,
                                       Integer inputTokens,
                                       Integer outputTokens,
                                       List<CardDraft> drafts,
                                       int droppedEmpty,
                                       int droppedExact,
                                       int droppedPrimary,
                                       int droppedSemantic,
                                       SourceCoverage sourceCoverage,
                                       List<UsageEvent> usageEvents) {
    }

    private record UsageEvent(String stage,
                              int attempt,
                              int requestedCount,
                              int candidateCount,
                              String model,
                              Integer inputTokens,
                              Integer outputTokens,
                              Integer cachedInputTokens,
                              Integer reasoningOutputTokens,
                              long durationMs) {
    }

    private record SourceCoverage(int sourceItemsTotal,
                                  int sourceItemsUsed,
                                  List<Integer> missingSourceIndexes,
                                  int alteredSourceItems,
                                  List<Integer> missingNumberedItems) {
    }

    private record SourceDraftValidation(boolean valid,
                                         List<CardDraft> orderedDrafts,
                                         List<Integer> missingSourceIndexes,
                                         List<String> alteredSourceTexts,
                                         int duplicateSourceIndexes,
                                         int unexpectedSourceIndexes) {
    }

    @FunctionalInterface
    private interface BatchPromptFactory {
        String build(int offset, int count);
    }

    private record MissingCardSelection(List<CoreUserCardResponse> cards,
                                        Map<UUID, Set<String>> allowedFieldsByCard) {
    }

    private record ImageConfig(boolean enabled,
                               String model,
                               String size,
                               String quality,
                               String style,
                               String format) {
    }

    private record VideoConfig(boolean enabled,
                               String model,
                               Integer durationSeconds,
                               String resolution,
                               String format) {
    }

    private record MediaUpload(UUID mediaId, String contentType, String fileName) {
    }

    private String buildCardsPrompt(String userPrompt,
                                    CoreTemplateResponse template,
                                    CorePublicDeckResponse publicDeck,
                                    List<String> fields,
                                    int count,
                                    String fewShotExamples,
                                    boolean compactPrompt) {
        String fieldList = String.join(", ", fields);
        StringBuilder builder = new StringBuilder();
        builder.append("You are generating flashcards for a deck template. ");
        builder.append("Return JSON that strictly matches the provided schema. ");
        builder.append("Use only these fields: ").append(fieldList).append(". ");
        builder.append("Create exactly ").append(count).append(" cards. ");
        builder.append("If a field is unknown or not applicable, return an empty string for that field. ");
        if (hasFieldType(template, fields, "image") || hasFieldType(template, fields, "video")) {
            builder.append("For image or video fields, return a short visual prompt describing the scene. ");
            builder.append("Do not include URLs, markdown, or file names. ");
            if (hasFieldType(template, fields, "video")) {
                builder.append("For video fields, describe a short 3-6 second clip. ");
            }
        }

        String deckName = publicDeck == null ? null : publicDeck.name();
        String deckDescription = publicDeck == null ? null : limitPromptText(publicDeck.description(), compactPrompt ? 180 : 600);
        String deckLanguage = publicDeck == null ? null : publicDeck.language();
        if (deckName != null && !deckName.isBlank()) {
            builder.append("Deck name: ").append(deckName.trim()).append(". ");
        }
        if (deckDescription != null && !deckDescription.isBlank()) {
            builder.append("Deck description: ").append(deckDescription.trim()).append(". ");
        }
        if (deckLanguage != null && !deckLanguage.isBlank()) {
            builder.append("Deck language: ").append(deckLanguage.trim()).append(". ");
        }

        if (template != null) {
            String templateName = template.name();
            String templateDescription = limitPromptText(template.description(), compactPrompt ? 180 : 600);
            if (templateName != null && !templateName.isBlank()) {
                builder.append("Template name: ").append(templateName.trim()).append(". ");
            }
            if (templateDescription != null && !templateDescription.isBlank()) {
                builder.append("Template description: ").append(templateDescription.trim()).append(". ");
            }
            String fieldHints = buildFieldHints(template, fields);
            if (!fieldHints.isBlank()) {
                builder.append("Field hints: ").append(fieldHints).append(". ");
            }
            String profile = compactPrompt
                    ? formatAiProfileLimited(template.aiProfile(), 240)
                    : formatAiProfile(template.aiProfile());
            if (!profile.isBlank()) {
                builder.append("Template AI profile: ").append(profile).append(". ");
            }
        }

        if (!compactPrompt && fewShotExamples != null && !fewShotExamples.isBlank()) {
            builder.append("Examples from existing cards: ").append(fewShotExamples).append(". ");
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            builder.append("User instructions: ").append(userPrompt.trim());
        }
        return builder.toString().trim();
    }

    private String limitPromptText(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, maxChars) + "...";
    }

    private JsonNode buildCardsSchema(List<String> fields, int count) {
        return buildCardsSchema(fields, count, false);
    }

    private JsonNode buildCardsSchema(List<String> fields, int count, boolean includeSourceTracking) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode cards = properties.putObject("cards");
        cards.put("type", "array");
        cards.put("minItems", count);
        cards.put("maxItems", count);
        ObjectNode items = cards.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        if (includeSourceTracking) {
            itemProps.putObject("sourceIndex").put("type", "integer");
            itemProps.putObject("sourceText").put("type", "string");
        }
        ObjectNode fieldsNode = itemProps.putObject("fields");
        fieldsNode.put("type", "object");
        ObjectNode fieldProps = fieldsNode.putObject("properties");
        for (String field : fields) {
            fieldProps.putObject(field).put("type", "string");
        }
        fieldsNode.put("additionalProperties", false);
        ArrayNode requiredFields = fieldsNode.putArray("required");
        for (String field : fields) {
            requiredFields.add(field);
        }
        ArrayNode itemRequired = items.putArray("required");
        if (includeSourceTracking) {
            itemRequired.add("sourceIndex");
            itemRequired.add("sourceText");
        }
        itemRequired.add("fields");
        items.put("additionalProperties", false);
        schema.put("additionalProperties", false);
        schema.putArray("required").add("cards");

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.put("name", "mnema_cards");
        responseFormat.set("schema", schema);
        responseFormat.put("strict", true);
        return responseFormat;
    }

    private JsonNode parseJsonResponse(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            throw new IllegalStateException("AI response is empty");
        }
        try {
            return objectMapper.readTree(outputText);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse AI response", ex);
        }
    }

    private List<CardDraft> buildCardDrafts(JsonNode response,
                                            List<String> fields,
                                            CoreTemplateResponse template,
                                            Map<String, String> fieldTypes) {
        return buildCardDrafts(response, fields, template, fieldTypes, false);
    }

    private List<CardDraft> buildCardDrafts(JsonNode response,
                                            List<String> fields,
                                            CoreTemplateResponse template,
                                            Map<String, String> fieldTypes,
                                            boolean includeSourceTracking) {
        JsonNode cardsNode = response.path("cards");
        if (!cardsNode.isArray()) {
            throw new IllegalStateException("AI response missing cards array");
        }
        List<CardDraft> drafts = new java.util.ArrayList<>();
        for (JsonNode cardNode : cardsNode) {
            JsonNode fieldsNode = cardNode.path("fields");
            if (!fieldsNode.isObject()) {
                continue;
            }
            Integer sourceIndex = includeSourceTracking && cardNode.path("sourceIndex").canConvertToInt()
                    ? cardNode.path("sourceIndex").asInt()
                    : null;
            String sourceText = includeSourceTracking ? textOrNull(cardNode.path("sourceText")) : null;
            ObjectNode content = objectMapper.createObjectNode();
            Map<String, String> mediaPrompts = new LinkedHashMap<>();
            boolean hasValue = false;
            for (String field : fields) {
                JsonNode valueNode = fieldsNode.get(field);
                if (valueNode == null || valueNode.isNull()) {
                    continue;
                }
                if (valueNode.isTextual()) {
                    String value = valueNode.asText().trim();
                    if (!value.isEmpty()) {
                        String fieldType = fieldTypes.get(field);
                        if (fieldType == null || isTextFieldType(fieldType)) {
                            content.put(field, value);
                            hasValue = true;
                        } else if ("image".equals(fieldType) || "video".equals(fieldType)) {
                            mediaPrompts.put(field, value);
                            hasValue = true;
                        }
                    }
                }
            }
            if (!hasValue) {
                continue;
            }
            ankiSupport.applyIfPresent(content, template);
            drafts.add(new CardDraft(content, mediaPrompts, sourceIndex, sourceText));
        }
        return drafts;
    }

    private String appendSourceValidationHint(String userPrompt, SourceDraftValidation validation) {
        if (validation == null || validation.valid()) {
            return userPrompt;
        }
        String hint = "Previous response failed source coverage validation. "
                + "Regenerate every requested item exactly once, preserve sourceIndex/sourceText exactly, "
                + "and put sourceText verbatim in a text field. "
                + summarizeSourceValidation(validation);
        if (userPrompt == null || userPrompt.isBlank()) {
            return hint;
        }
        return userPrompt.trim() + "\n\n" + hint;
    }

    private SourceDraftValidation validateSourceDrafts(List<CardDraft> drafts,
                                                       List<ImportItemExtractor.SourceItem> sourceItems,
                                                       List<String> allowedFields) {
        if (sourceItems == null || sourceItems.isEmpty()) {
            return new SourceDraftValidation(true, drafts == null ? List.of() : List.copyOf(drafts), List.of(), List.of(), 0, 0);
        }
        Map<Integer, ImportItemExtractor.SourceItem> expectedByIndex = new LinkedHashMap<>();
        for (ImportItemExtractor.SourceItem item : sourceItems) {
            expectedByIndex.put(item.sourceIndex(), item);
        }
        Map<Integer, CardDraft> acceptedByIndex = new LinkedHashMap<>();
        List<Integer> missingIndexes = new ArrayList<>();
        List<String> alteredTexts = new ArrayList<>();
        int duplicateSourceIndexes = 0;
        int unexpectedSourceIndexes = 0;
        if (drafts != null) {
            for (CardDraft draft : drafts) {
                if (draft == null || draft.sourceIndex() == null) {
                    unexpectedSourceIndexes++;
                    continue;
                }
                ImportItemExtractor.SourceItem expected = expectedByIndex.get(draft.sourceIndex());
                if (expected == null) {
                    unexpectedSourceIndexes++;
                    continue;
                }
                if (acceptedByIndex.containsKey(draft.sourceIndex())) {
                    duplicateSourceIndexes++;
                    continue;
                }
                String normalizedSource = ImportItemExtractor.normalizeKey(draft.sourceText());
                boolean sourceTextMatches = expected.normalizedKey().equals(normalizedSource);
                boolean contentContainsSource = draftContainsSourceText(draft, expected, allowedFields);
                if (!sourceTextMatches || !contentContainsSource) {
                    alteredTexts.add(expected.sourceIndex() + ": " + expected.text());
                    continue;
                }
                acceptedByIndex.put(draft.sourceIndex(), draft);
            }
        }
        for (ImportItemExtractor.SourceItem expected : sourceItems) {
            if (!acceptedByIndex.containsKey(expected.sourceIndex())) {
                missingIndexes.add(expected.sourceIndex());
            }
        }
        List<CardDraft> orderedDrafts = sourceItems.stream()
                .map(item -> acceptedByIndex.get(item.sourceIndex()))
                .filter(Objects::nonNull)
                .toList();
        boolean valid = missingIndexes.isEmpty()
                && alteredTexts.isEmpty()
                && duplicateSourceIndexes == 0
                && unexpectedSourceIndexes == 0
                && orderedDrafts.size() == sourceItems.size();
        return new SourceDraftValidation(valid, orderedDrafts, missingIndexes, alteredTexts, duplicateSourceIndexes, unexpectedSourceIndexes);
    }

    private boolean draftContainsSourceText(CardDraft draft,
                                            ImportItemExtractor.SourceItem expected,
                                            List<String> allowedFields) {
        if (draft == null || draft.content() == null || expected == null) {
            return false;
        }
        String expectedKey = expected.normalizedKey();
        if (expectedKey.isBlank()) {
            return false;
        }
        for (String field : allowedFields) {
            JsonNode value = draft.content().get(field);
            if (value == null || !value.isTextual()) {
                continue;
            }
            String normalizedValue = ImportItemExtractor.normalizeKey(value.asText());
            if (normalizedValue.equals(expectedKey) || normalizedValue.contains(expectedKey)) {
                return true;
            }
        }
        return false;
    }

    private String summarizeSourceValidation(SourceDraftValidation validation) {
        if (validation == null) {
            return "no validation details";
        }
        return "missingSourceIndexes=" + validation.missingSourceIndexes()
                + ", alteredSourceItems=" + validation.alteredSourceTexts().size()
                + ", duplicateSourceIndexes=" + validation.duplicateSourceIndexes()
                + ", unexpectedSourceIndexes=" + validation.unexpectedSourceIndexes();
    }

    private String buildFieldHints(CoreTemplateResponse template, List<String> fields) {
        if (template == null || template.fields() == null || template.fields().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String fieldName : fields) {
            CoreFieldTemplate field = template.fields().stream()
                    .filter(f -> f != null && fieldName.equals(f.name()))
                    .findFirst()
                    .orElse(null);
            if (field == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("; ");
            }
            String label = field.label();
            if (label != null && !label.isBlank() && !label.equals(fieldName)) {
                builder.append(fieldName).append(" (").append(label.trim()).append(")");
            } else {
                builder.append(fieldName);
            }
        }
        return builder.toString();
    }

    private boolean hasFieldType(CoreTemplateResponse template, List<String> fields, String targetType) {
        if (template == null || template.fields() == null || fields == null || fields.isEmpty()) {
            return false;
        }
        for (String fieldName : fields) {
            if (fieldName == null) {
                continue;
            }
            for (CoreFieldTemplate field : template.fields()) {
                if (field == null || field.name() == null || field.fieldType() == null) {
                    continue;
                }
                if (fieldName.equals(field.name()) && targetType.equals(field.fieldType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String formatAiProfile(JsonNode aiProfile) {
        return formatAiProfileLimited(aiProfile, 800);
    }

    private String formatAiProfileLimited(JsonNode aiProfile, int max) {
        if (aiProfile == null || aiProfile.isNull()) {
            return "";
        }
        String raw = aiProfile.isTextual() ? aiProfile.asText() : aiProfile.toString();
        String trimmed = raw.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "...";
    }

    private String buildFewShotExamples(UUID deckId, String accessToken, List<String> fields) {
        if (deckId == null || accessToken == null || accessToken.isBlank()) {
            return "";
        }
        CoreUserCardPage page = coreApiClient.getUserCards(deckId, 1, 3, accessToken);
        if (page == null || page.content() == null || page.content().isEmpty()) {
            return "";
        }
        var examples = new java.util.ArrayList<ObjectNode>();
        for (CoreUserCardResponse card : page.content()) {
            if (card == null || card.effectiveContent() == null || card.effectiveContent().isNull()) {
                continue;
            }
            ObjectNode example = objectMapper.createObjectNode();
            boolean hasValue = false;
            for (String field : fields) {
                JsonNode value = card.effectiveContent().get(field);
                if (value != null && value.isTextual()) {
                    String text = value.asText().trim();
                    if (!text.isEmpty()) {
                        example.put(field, text);
                        hasValue = true;
                    }
                }
            }
            if (hasValue) {
                examples.add(example);
            }
        }
        if (examples.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(examples);
        } catch (Exception ex) {
            return "";
        }
    }

    private TtsApplyResult applyTtsIfNeeded(AiJobEntity job,
                                            String apiKey,
                                            JsonNode params,
                                            List<CoreUserCardResponse> createdCards,
                                            CoreTemplateResponse template,
                                            String updateScope) {
        JsonNode ttsNode = params.path("tts");
        if (!ttsNode.path("enabled").asBoolean(false)) {
            return new TtsApplyResult(0, 0, null, null);
        }
        if (createdCards == null || createdCards.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        String accessToken = job.getUserAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        List<String> audioFields = resolveAudioFields(template);
        if (audioFields.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        List<String> textFields = resolveTextFields(template);
        if (textFields.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }

        List<TtsMapping> mappings = resolveTtsMappings(ttsNode, textFields, audioFields, template);
        if (mappings.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }

        String model = textOrDefault(ttsNode.path("model"), props.defaultTtsModel());
        if (model == null || model.isBlank()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        String voice = textOrNull(ttsNode.path("voice"));
        if (voice == null && props.defaultVoice() != null && !props.defaultVoice().isBlank()) {
            voice = props.defaultVoice().trim();
        }
        String format = textOrDefault(ttsNode.path("format"), props.defaultTtsFormat());
        if (format == null || format.isBlank()) {
            format = "mp3";
        }
        int maxChars = ttsNode.path("maxChars").isInt() ? ttsNode.path("maxChars").asInt() : 300;
        if (maxChars < 1) {
            maxChars = 1;
        }

        int generated = 0;
        int charsGenerated = 0;
        int updatedCards = 0;
        String ttsError = null;
        Set<UUID> updatedCardIds = new LinkedHashSet<>();
        Map<UUID, List<String>> cardErrors = new LinkedHashMap<>();
        for (CoreUserCardResponse card : createdCards) {
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = loadLatestContent(job.getJobId(), job.getDeckId(), card.userCardId(), accessToken, card.effectiveContent().deepCopy());
            boolean updated = false;
            for (TtsMapping mapping : mappings) {
                String text = sanitizeTtsText(extractTextValue(updatedContent, mapping.sourceField()));
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (text.length() > maxChars) {
                    continue;
                }
                byte[] audio;
                try {
                    audio = createSpeechWithRetry(
                            job,
                            apiKey,
                            new OpenAiSpeechRequest(model, text, voice, format),
                            card.userCardId(),
                            mapping.targetField()
                    );
                } catch (Exception ex) {
                    appendCardError(cardErrors, card.userCardId(), mapping.targetField(), summarizeError(ex));
                    if (ttsError == null) {
                        ttsError = summarizeError(ex);
                        LOGGER.warn("OpenAI TTS failed jobId={} cardId={} field={} error={}",
                                job.getJobId(),
                                card.userCardId(),
                                mapping.targetField(),
                                ttsError);
                    }
                    continue;
                }
                String contentType = resolveAudioContentType(format);
                String fileName = "ai-tts-" + job.getJobId() + "-" + card.userCardId() + "." + format;
                UUID mediaId = mediaApiClient.directUpload(
                        job.getUserId(),
                        "card_audio",
                        contentType,
                        fileName,
                        audio.length,
                        new ByteArrayInputStream(audio)
                );
                ObjectNode audioNode = objectMapper.createObjectNode();
                audioNode.put("mediaId", mediaId.toString());
                audioNode.put("kind", "audio");
                updatedContent.set(mapping.targetField(), audioNode);
                updated = true;
                generated++;
                charsGenerated += text.length();
            }
            if (updated) {
                UpdateUserCardRequest update = new UpdateUserCardRequest(
                        card.userCardId(),
                        null,
                        false,
                        false,
                        null,
                        updatedContent
                );
                String cardScope = resolveCardUpdateScope(updateScope, card);
                UUID operationId = resolveUpdateOperationId(cardScope, job);
                coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), update, accessToken, cardScope, operationId);
                updatedCards++;
                updatedCardIds.add(card.userCardId());
            }
        }
        return new TtsApplyResult(generated, updatedCards, charsGenerated, model, ttsError, updatedCardIds, cardErrors);
    }

    private TtsApplyResult applyTtsForMissingAudio(AiJobEntity job,
                                                   String apiKey,
                                                   JsonNode params,
                                                   List<CoreUserCardResponse> cards,
                                                   CoreTemplateResponse template,
                                                   List<String> targetFields,
                                                   String updateScope) {
        JsonNode ttsNode = params.path("tts");
        if (!ttsNode.path("enabled").asBoolean(false)) {
            return new TtsApplyResult(0, 0, null, null);
        }
        if (cards == null || cards.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        String accessToken = job.getUserAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        List<String> audioFields = resolveAudioFields(template);
        if (audioFields.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        List<String> textFields = resolveTextFields(template);
        if (textFields.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }

        List<TtsMapping> mappings = resolveTtsMappings(ttsNode, textFields, audioFields, template).stream()
                .filter(mapping -> targetFields.contains(mapping.targetField()))
                .toList();
        if (mappings.isEmpty()) {
            return new TtsApplyResult(0, 0, null, null);
        }

        String model = textOrDefault(ttsNode.path("model"), props.defaultTtsModel());
        if (model == null || model.isBlank()) {
            return new TtsApplyResult(0, 0, null, null);
        }
        String voice = textOrNull(ttsNode.path("voice"));
        if (voice == null && props.defaultVoice() != null && !props.defaultVoice().isBlank()) {
            voice = props.defaultVoice().trim();
        }
        String format = textOrDefault(ttsNode.path("format"), props.defaultTtsFormat());
        if (format == null || format.isBlank()) {
            format = "mp3";
        }
        int maxChars = ttsNode.path("maxChars").isInt() ? ttsNode.path("maxChars").asInt() : 300;
        if (maxChars < 1) {
            maxChars = 1;
        }

        int generated = 0;
        int charsGenerated = 0;
        int updatedCards = 0;
        String ttsError = null;
        Set<UUID> updatedCardIds = new LinkedHashSet<>();
        Map<UUID, List<String>> cardErrors = new LinkedHashMap<>();
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = card.effectiveContent().deepCopy();
            boolean updated = false;
            for (TtsMapping mapping : mappings) {
                if (!isMissingAudio(updatedContent.get(mapping.targetField()))) {
                    continue;
                }
                String text = sanitizeTtsText(extractTextValue(card.effectiveContent(), mapping.sourceField()));
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (text.length() > maxChars) {
                    continue;
                }
                byte[] audio;
                try {
                    audio = createSpeechWithRetry(
                            job,
                            apiKey,
                            new OpenAiSpeechRequest(model, text, voice, format),
                            card.userCardId(),
                            mapping.targetField()
                    );
                } catch (Exception ex) {
                    appendCardError(cardErrors, card.userCardId(), mapping.targetField(), summarizeError(ex));
                    if (ttsError == null) {
                        ttsError = summarizeError(ex);
                        LOGGER.warn("OpenAI TTS failed jobId={} cardId={} field={} error={}",
                                job.getJobId(),
                                card.userCardId(),
                                mapping.targetField(),
                                ttsError);
                    }
                    continue;
                }
                String contentType = resolveAudioContentType(format);
                String fileName = "ai-tts-" + job.getJobId() + "-" + card.userCardId() + "." + format;
                UUID mediaId = mediaApiClient.directUpload(
                        job.getUserId(),
                        "card_audio",
                        contentType,
                        fileName,
                        audio.length,
                        new ByteArrayInputStream(audio)
                );
                ObjectNode audioNode = objectMapper.createObjectNode();
                audioNode.put("mediaId", mediaId.toString());
                audioNode.put("kind", "audio");
                updatedContent.set(mapping.targetField(), audioNode);
                updated = true;
                generated++;
                charsGenerated += text.length();
            }
            if (updated) {
                ankiSupport.applyIfPresent(updatedContent, template);
                UpdateUserCardRequest update = new UpdateUserCardRequest(
                        card.userCardId(),
                        null,
                        false,
                        false,
                        null,
                        updatedContent
                );
                String cardScope = resolveCardUpdateScope(updateScope, card);
                UUID operationId = resolveUpdateOperationId(cardScope, job);
                coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), update, accessToken, cardScope, operationId);
                updatedCards++;
                updatedCardIds.add(card.userCardId());
            }
        }
        return new TtsApplyResult(generated, updatedCards, charsGenerated, model, ttsError, updatedCardIds, cardErrors);
    }

    private DraftTtsPreparationResult applyTtsToDrafts(AiJobEntity job,
                                                       String apiKey,
                                                       JsonNode params,
                                                       List<CardDraft> drafts,
                                                       CoreTemplateResponse template) {
        JsonNode ttsNode = params.path("tts");
        if (!ttsNode.path("enabled").asBoolean(false)) {
            return new DraftTtsPreparationResult(0, 0, null, null, Set.of(), Map.of());
        }
        if (drafts == null || drafts.isEmpty()) {
            return new DraftTtsPreparationResult(0, 0, null, null, Set.of(), Map.of());
        }
        int generated = 0;
        int charsGenerated = 0;
        String model = null;
        String error = null;
        Set<Integer> updatedDraftIndexes = new LinkedHashSet<>();
        Map<Integer, List<String>> draftErrors = new LinkedHashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            CardDraft draft = drafts.get(i);
            if (draft == null || draft.content() == null) {
                continue;
            }
            TtsContentApplyResult result = applyTtsToContent(
                    job,
                    apiKey,
                    ttsNode,
                    draft.content(),
                    template,
                    null,
                    null,
                    "draft-" + i
            );
            generated += result.generated();
            charsGenerated += result.charsGenerated();
            if (model == null) {
                model = result.model();
            }
            if (error == null && !result.errors().isEmpty()) {
                error = result.errors().getFirst();
            }
            if (result.updated()) {
                ankiSupport.applyIfPresent(draft.content(), template);
                updatedDraftIndexes.add(i);
            }
            if (!result.errors().isEmpty()) {
                draftErrors.put(i, new ArrayList<>(result.errors()));
            }
            executionService.updateStepProgress(
                    job.getJobId(),
                    STEP_GENERATE_AUDIO,
                    (i + 1) / (double) Math.max(1, drafts.size())
            );
        }
        return new DraftTtsPreparationResult(generated, charsGenerated, model, error, updatedDraftIndexes, draftErrors);
    }

    private TtsContentApplyResult applyTtsToContent(AiJobEntity job,
                                                    String apiKey,
                                                    JsonNode ttsNode,
                                                    ObjectNode updatedContent,
                                                    CoreTemplateResponse template,
                                                    List<String> targetFields,
                                                    UUID cardId,
                                                    String fileToken) {
        if (ttsNode == null || !ttsNode.path("enabled").asBoolean(false)) {
            return new TtsContentApplyResult(false, 0, 0, null, List.of());
        }
        if (updatedContent == null || template == null) {
            return new TtsContentApplyResult(false, 0, 0, null, List.of());
        }
        List<String> audioFields = resolveAudioFields(template);
        if (audioFields.isEmpty()) {
            return new TtsContentApplyResult(false, 0, 0, null, List.of());
        }
        List<String> textFields = resolveTextFields(template);
        if (textFields.isEmpty()) {
            return new TtsContentApplyResult(false, 0, 0, null, List.of());
        }

        List<TtsMapping> mappings = resolveTtsMappings(ttsNode, textFields, audioFields, template);
        if (targetFields != null && !targetFields.isEmpty()) {
            Set<String> allowedTargets = new LinkedHashSet<>(targetFields);
            mappings = mappings.stream()
                    .filter(mapping -> allowedTargets.contains(mapping.targetField()))
                    .toList();
        }
        if (mappings.isEmpty()) {
            return new TtsContentApplyResult(false, 0, 0, null, List.of());
        }

        String model = textOrDefault(ttsNode.path("model"), props.defaultTtsModel());
        if (model == null || model.isBlank()) {
            return new TtsContentApplyResult(false, 0, 0, null, List.of());
        }
        String voice = textOrNull(ttsNode.path("voice"));
        if (voice == null && props.defaultVoice() != null && !props.defaultVoice().isBlank()) {
            voice = props.defaultVoice().trim();
        }
        String format = textOrDefault(ttsNode.path("format"), props.defaultTtsFormat());
        if (format == null || format.isBlank()) {
            format = "mp3";
        }
        int maxChars = ttsNode.path("maxChars").isInt() ? ttsNode.path("maxChars").asInt() : 300;
        if (maxChars < 1) {
            maxChars = 1;
        }

        boolean updated = false;
        int generated = 0;
        int charsGenerated = 0;
        List<String> errors = new ArrayList<>();
        for (TtsMapping mapping : mappings) {
            if (!isMissingAudio(updatedContent.get(mapping.targetField()))) {
                continue;
            }
            String text = sanitizeTtsText(extractTextValue(updatedContent, mapping.sourceField()));
            if (text == null || text.isBlank() || text.length() > maxChars) {
                continue;
            }
            byte[] audio;
            try {
                audio = createSpeechWithRetry(
                        job,
                        apiKey,
                        new OpenAiSpeechRequest(model, text, voice, format),
                        cardId,
                        mapping.targetField()
                );
            } catch (Exception ex) {
                String normalized = summarizeError(ex);
                errors.add(formatFieldError(mapping.targetField(), normalized));
                LOGGER.warn("OpenAI TTS failed jobId={} cardId={} field={} error={}",
                        job.getJobId(),
                        cardId,
                        mapping.targetField(),
                        normalized);
                continue;
            }
            String contentType = resolveAudioContentType(format);
            String fileName = "ai-tts-" + job.getJobId() + "-" + fileToken + "-" + mapping.targetField() + "." + format;
            UUID mediaId = mediaApiClient.directUpload(
                    job.getUserId(),
                    "card_audio",
                    contentType,
                    fileName,
                    audio.length,
                    new ByteArrayInputStream(audio)
            );
            updatedContent.set(mapping.targetField(), buildMediaNode(mediaId, "audio"));
            updated = true;
            generated++;
            charsGenerated += text.length();
        }
        return new TtsContentApplyResult(updated, generated, charsGenerated, model, errors);
    }

    private List<String> resolveAudioFields(CoreTemplateResponse template) {
        if (template == null || template.fields() == null) {
            return List.of();
        }
        return template.fields().stream()
                .filter(field -> field != null && "audio".equals(field.fieldType()))
                .map(CoreFieldTemplate::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private List<String> resolveTextFields(CoreTemplateResponse template) {
        if (template == null || template.fields() == null) {
            return List.of();
        }
        return template.fields().stream()
                .filter(this::isTextField)
                .map(CoreFieldTemplate::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private Map<String, String> resolveFieldTypes(CoreTemplateResponse template) {
        if (template == null || template.fields() == null) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (CoreFieldTemplate field : template.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) {
                continue;
            }
            if (field.fieldType() != null && !field.fieldType().isBlank()) {
                map.put(field.name(), field.fieldType());
            }
        }
        return map;
    }

    private boolean isTextFieldType(String fieldType) {
        if (fieldType == null) {
            return true;
        }
        return switch (fieldType) {
            case "text", "rich_text", "markdown", "cloze" -> true;
            default -> false;
        };
    }

    private boolean isMissingMedia(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().trim().isEmpty();
        }
        if (node.isObject()) {
            JsonNode mediaId = node.get("mediaId");
            return mediaId == null || mediaId.asText().trim().isEmpty();
        }
        if (node.isArray()) {
            return node.size() == 0;
        }
        return false;
    }

    private ObjectNode buildMediaNode(UUID mediaId, String kind) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("mediaId", mediaId.toString());
        node.put("kind", kind);
        return node;
    }

    private MissingCardSelection selectMissingCards(UUID deckId,
                                                    Set<String> targetFields,
                                                    Map<String, String> fieldTypes,
                                                    JsonNode params,
                                                    String accessToken) {
        Set<UUID> requestedCardIds = resolveRequestedCardIds(params);
        if (!requestedCardIds.isEmpty()) {
            return selectRequestedCards(deckId, requestedCardIds, targetFields, fieldTypes, params, accessToken);
        }
        Map<String, Integer> fieldLimits = extractFieldLimits(params);
        if (!fieldLimits.isEmpty()) {
            LinkedHashMap<String, Integer> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : fieldLimits.entrySet()) {
                if (targetFields.contains(entry.getKey())) {
                    filtered.put(entry.getKey(), entry.getValue());
                }
            }
            return selectMissingCardsWithLimits(deckId, filtered, accessToken);
        }
        int limit = resolveLimit(params.path("limit"));
        List<CoreUserCardResponse> cards = coreApiClient.getMissingFieldCards(
                deckId,
                new CoreApiClient.MissingFieldCardsRequest(new ArrayList<>(targetFields), limit, null),
                accessToken
        );
        return new MissingCardSelection(cards, Map.of());
    }

    private MissingCardSelection selectRequestedCards(UUID deckId,
                                                      Set<UUID> requestedCardIds,
                                                      Set<String> targetFields,
                                                      Map<String, String> fieldTypes,
                                                      JsonNode params,
                                                      String accessToken) {
        if (requestedCardIds == null || requestedCardIds.isEmpty()) {
            return new MissingCardSelection(List.of(), Map.of());
        }
        Set<String> limitedFields = extractFieldLimits(params).keySet();
        List<String> orderedFields = new ArrayList<>(targetFields);
        LinkedHashMap<UUID, CoreUserCardResponse> cards = new LinkedHashMap<>();
        Map<UUID, Set<String>> allowedFieldsByCard = new HashMap<>();
        for (UUID cardId : requestedCardIds) {
            if (cardId == null) {
                continue;
            }
            try {
                CoreApiClient.CoreUserCardDetail detail = coreApiClient.getUserCard(deckId, cardId, accessToken);
                if (detail == null || detail.effectiveContent() == null || !detail.effectiveContent().isObject()) {
                    continue;
                }
                LinkedHashSet<String> missingFields = new LinkedHashSet<>();
                for (String field : orderedFields) {
                    if (field == null || field.isBlank()) {
                        continue;
                    }
                    if (!limitedFields.isEmpty() && !limitedFields.contains(field)) {
                        continue;
                    }
                    if (isFieldMissing(detail.effectiveContent().get(field), fieldTypes.get(field))) {
                        missingFields.add(field);
                    }
                }
                if (missingFields.isEmpty()) {
                    continue;
                }
                cards.put(cardId, new CoreUserCardResponse(detail.userCardId(), detail.publicCardId(), detail.isCustom(), detail.effectiveContent()));
                allowedFieldsByCard.put(cardId, missingFields);
            } catch (Exception ex) {
                LOGGER.warn("OpenAI retry selection failed deckId={} cardId={}", deckId, cardId, ex);
            }
        }
        return new MissingCardSelection(new ArrayList<>(cards.values()), allowedFieldsByCard);
    }

    private Set<UUID> resolveRequestedCardIds(JsonNode params) {
        if (params == null || params.isNull()) {
            return Set.of();
        }
        JsonNode node = params.get("cardIds");
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        LinkedHashSet<UUID> cardIds = new LinkedHashSet<>();
        for (JsonNode item : node) {
            UUID parsed = parseUuid(item == null ? null : item.asText(null));
            if (parsed != null) {
                cardIds.add(parsed);
            }
        }
        return Set.copyOf(cardIds);
    }

    private boolean isFieldMissing(JsonNode value, String fieldType) {
        if ("audio".equals(fieldType)) {
            return isMissingAudio(value);
        }
        if ("image".equals(fieldType) || "video".equals(fieldType)) {
            return isMissingMedia(value);
        }
        return isMissingText(value);
    }

    private MissingCardSelection selectMissingCardsWithLimits(UUID deckId,
                                                              Map<String, Integer> fieldLimits,
                                                              String accessToken) {
        if (fieldLimits.isEmpty()) {
            return new MissingCardSelection(List.of(), Map.of());
        }
        LinkedHashMap<UUID, CoreUserCardResponse> cards = new LinkedHashMap<>();
        Map<UUID, Set<String>> allowedFields = new HashMap<>();
        for (Map.Entry<String, Integer> entry : fieldLimits.entrySet()) {
            String field = entry.getKey();
            int limit = Math.max(1, Math.min(entry.getValue(), 200));
            List<CoreUserCardResponse> missing = coreApiClient.getMissingFieldCards(
                    deckId,
                    new CoreApiClient.MissingFieldCardsRequest(List.of(field), limit, null),
                    accessToken
            );
            for (CoreUserCardResponse card : missing) {
                if (card == null || card.userCardId() == null) {
                    continue;
                }
                cards.putIfAbsent(card.userCardId(), card);
                allowedFields.computeIfAbsent(card.userCardId(), __ -> new LinkedHashSet<>()).add(field);
            }
        }
        return new MissingCardSelection(new ArrayList<>(cards.values()), allowedFields);
    }

    private List<CoreUserCardResponse> filterCardsForPrompt(List<CoreUserCardResponse> cards,
                                                            List<String> fields,
                                                            Map<String, String> fieldTypes,
                                                            Map<UUID, Set<String>> allowedFieldsByCard) {
        if (fields.isEmpty()) {
            return List.of();
        }
        List<CoreUserCardResponse> result = new ArrayList<>();
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.userCardId() == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            Set<String> allowed = allowedFieldsByCard.get(card.userCardId());
            boolean needsPrompt = false;
            for (String field : fields) {
                if (allowed != null && !allowed.isEmpty() && !allowed.contains(field)) {
                    continue;
                }
                String fieldType = fieldTypes.get(field);
                JsonNode value = card.effectiveContent().get(field);
                boolean missing = (fieldType == null || isTextFieldType(fieldType))
                        ? isMissingText(value)
                        : isMissingMedia(value);
                if (missing) {
                    needsPrompt = true;
                    break;
                }
            }
            if (needsPrompt) {
                result.add(card);
            }
        }
        return result;
    }

    private List<CoreUserCardResponse> filterCardsForAudio(List<CoreUserCardResponse> cards,
                                                           List<String> audioFields,
                                                           Map<UUID, Set<String>> allowedFieldsByCard) {
        if (audioFields.isEmpty()) {
            return List.of();
        }
        List<CoreUserCardResponse> result = new ArrayList<>();
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.userCardId() == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            Set<String> allowed = allowedFieldsByCard.get(card.userCardId());
            boolean needsAudio = false;
            for (String field : audioFields) {
                if (allowed != null && !allowed.isEmpty() && !allowed.contains(field)) {
                    continue;
                }
                if (isMissingAudio(card.effectiveContent().get(field))) {
                    needsAudio = true;
                    break;
                }
            }
            if (needsAudio) {
                result.add(card);
            }
        }
        return result;
    }

    private MediaApplyResult applyMediaPromptsToNewCards(AiJobEntity job,
                                                        String apiKey,
                                                        String accessToken,
                                                        CoreTemplateResponse template,
                                                        List<CoreUserCardResponse> createdCards,
                                                        List<CardDraft> drafts,
                                                        Map<String, String> fieldTypes,
                                                        ImageConfig imageConfig,
                                                        VideoConfig videoConfig,
                                                        String updateScope) {
        if (createdCards == null || createdCards.isEmpty() || drafts == null || drafts.isEmpty()) {
            return new MediaApplyResult(0, 0, 0);
        }
        int updated = 0;
        int imagesGenerated = 0;
        int videosGenerated = 0;
        Set<UUID> updatedCardIds = new LinkedHashSet<>();
        Map<UUID, List<String>> cardErrors = new LinkedHashMap<>();
        int limit = Math.min(createdCards.size(), drafts.size());
        for (int i = 0; i < limit; i++) {
            CoreUserCardResponse card = createdCards.get(i);
            CardDraft draft = drafts.get(i);
            if (card == null || card.userCardId() == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            Map<String, String> mediaPrompts = draft.mediaPrompts();
            if (mediaPrompts == null || mediaPrompts.isEmpty()) {
                continue;
            }
            ObjectNode updatedContent = loadLatestContent(job.getJobId(), job.getDeckId(), card.userCardId(), accessToken, card.effectiveContent().deepCopy());
            boolean changed = false;
            for (Map.Entry<String, String> entry : mediaPrompts.entrySet()) {
                String field = entry.getKey();
                String prompt = entry.getValue();
                if (prompt == null || prompt.isBlank()) {
                    continue;
                }
                String fieldType = fieldTypes.get(field);
                if ("image".equals(fieldType)) {
                    if (!imageConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                        continue;
                    }
                    try {
                        MediaUpload upload = generateImage(job, apiKey, imageConfig, prompt.trim());
                        updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                        changed = true;
                        imagesGenerated++;
                    } catch (Exception ex) {
                        appendCardError(cardErrors, card.userCardId(), field, summarizeError(ex));
                        LOGGER.warn("OpenAI image generation failed jobId={} cardId={} field={} model={} promptLength={}",
                                job.getJobId(),
                                card.userCardId(),
                                field,
                                imageConfig.model(),
                                prompt.length(),
                                ex);
                    }
                    continue;
                }
                if ("video".equals(fieldType)) {
                    if (!videoConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                        continue;
                    }
                    try {
                        MediaUpload upload = generateVideo(job, apiKey, videoConfig, prompt.trim());
                        updatedContent.set(field, buildMediaNode(upload.mediaId(), "video"));
                        changed = true;
                        videosGenerated++;
                    } catch (Exception ex) {
                        appendCardError(cardErrors, card.userCardId(), field, summarizeError(ex));
                        LOGGER.warn("OpenAI video generation failed jobId={} cardId={} field={} model={} promptLength={}",
                                job.getJobId(),
                                card.userCardId(),
                                field,
                                videoConfig.model(),
                                prompt.length(),
                                ex);
                    }
                }
            }
            if (!changed) {
                continue;
            }
            ankiSupport.applyIfPresent(updatedContent, template);
            UpdateUserCardRequest update = new UpdateUserCardRequest(
                    card.userCardId(),
                    null,
                    false,
                    false,
                    null,
                    updatedContent
            );
            String cardScope = resolveCardUpdateScope(updateScope, card);
            UUID operationId = resolveUpdateOperationId(cardScope, job);
            coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), update, accessToken, cardScope, operationId);
            updated++;
            updatedCardIds.add(card.userCardId());
        }
        return new MediaApplyResult(updated, imagesGenerated, videosGenerated, updatedCardIds, cardErrors);
    }

    private DraftMediaPreparationResult prepareDraftMedia(AiJobEntity job,
                                                          String apiKey,
                                                          CoreTemplateResponse template,
                                                          List<CardDraft> drafts,
                                                          Map<String, String> fieldTypes,
                                                          ImageConfig imageConfig,
                                                          VideoConfig videoConfig) {
        if (drafts == null || drafts.isEmpty()) {
            return new DraftMediaPreparationResult(0, 0, Set.of(), Map.of());
        }
        int imagesGenerated = 0;
        int videosGenerated = 0;
        Set<Integer> updatedDraftIndexes = new LinkedHashSet<>();
        Map<Integer, List<String>> draftErrors = new LinkedHashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            CardDraft draft = drafts.get(i);
            if (draft == null || draft.content() == null) {
                continue;
            }
            ContentMutationResult result = applyDraftMediaToContent(
                    job,
                    apiKey,
                    draft.content(),
                    draft.mediaPrompts(),
                    fieldTypes,
                    imageConfig,
                    videoConfig,
                    "draft-" + i
            );
            if (result.changed()) {
                ankiSupport.applyIfPresent(draft.content(), template);
                updatedDraftIndexes.add(i);
            }
            imagesGenerated += result.imagesGenerated();
            videosGenerated += result.videosGenerated();
            if (!result.errors().isEmpty()) {
                draftErrors.put(i, new ArrayList<>(result.errors()));
            }
            executionService.updateStepProgress(
                    job.getJobId(),
                    STEP_GENERATE_MEDIA,
                    (i + 1) / (double) Math.max(1, drafts.size())
            );
        }
        return new DraftMediaPreparationResult(imagesGenerated, videosGenerated, updatedDraftIndexes, draftErrors);
    }

    private ContentMutationResult applyDraftMediaToContent(AiJobEntity job,
                                                           String apiKey,
                                                           ObjectNode updatedContent,
                                                           Map<String, String> mediaPrompts,
                                                           Map<String, String> fieldTypes,
                                                           ImageConfig imageConfig,
                                                           VideoConfig videoConfig,
                                                           String draftToken) {
        if (updatedContent == null || mediaPrompts == null || mediaPrompts.isEmpty()) {
            return new ContentMutationResult(false, 0, 0, List.of());
        }
        boolean changed = false;
        int imagesGenerated = 0;
        int videosGenerated = 0;
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String> entry : mediaPrompts.entrySet()) {
            String field = entry.getKey();
            String prompt = entry.getValue();
            if (prompt == null || prompt.isBlank()) {
                continue;
            }
            String fieldType = fieldTypes.get(field);
            if ("image".equals(fieldType)) {
                if (!imageConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                    continue;
                }
                try {
                    MediaUpload upload = generateImage(job, apiKey, imageConfig, prompt.trim());
                    updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                    changed = true;
                    imagesGenerated++;
                } catch (Exception ex) {
                    errors.add(formatFieldError(field, summarizeError(ex)));
                    LOGGER.warn("OpenAI image generation failed jobId={} draft={} field={} model={} promptLength={}",
                            job.getJobId(),
                            draftToken,
                            field,
                            imageConfig.model(),
                            prompt.length(),
                            ex);
                }
                continue;
            }
            if ("video".equals(fieldType)) {
                if (!videoConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                    continue;
                }
                try {
                    MediaUpload upload = generateVideo(job, apiKey, videoConfig, prompt.trim());
                    updatedContent.set(field, buildMediaNode(upload.mediaId(), "video"));
                    changed = true;
                    videosGenerated++;
                } catch (Exception ex) {
                    errors.add(formatFieldError(field, summarizeError(ex)));
                    LOGGER.warn("OpenAI video generation failed jobId={} draft={} field={} model={} promptLength={}",
                            job.getJobId(),
                            draftToken,
                            field,
                            videoConfig.model(),
                            prompt.length(),
                            ex);
                }
            }
        }
        return new ContentMutationResult(changed, imagesGenerated, videosGenerated, errors);
    }

    private ImageConfig resolveImageConfig(JsonNode params, boolean hasPromptFields) {
        JsonNode node = params.path("image");
        boolean enabled = hasPromptFields;
        if (node.path("enabled").isBoolean()) {
            enabled = node.path("enabled").asBoolean();
        }
        String model = textOrDefault(node.path("model"), props.defaultImageModel());
        if (model == null || model.isBlank()) {
            if (isLocalOllamaRequest(params)) {
                enabled = false;
                model = "";
            } else {
                model = "gpt-image-1-mini";
            }
        }
        String size = textOrDefault(node.path("size"), props.defaultImageSize());
        if (size == null || size.isBlank()) {
            size = "1024x1024";
        }
        String quality = textOrDefault(node.path("quality"), props.defaultImageQuality());
        String style = textOrDefault(node.path("style"), props.defaultImageStyle());
        String format = textOrDefault(node.path("format"), props.defaultImageFormat());
        if (format == null || format.isBlank()) {
            format = "png";
        }
        return new ImageConfig(enabled, model, size, quality, style, format);
    }

    private VideoConfig resolveVideoConfig(JsonNode params, boolean hasPromptFields) {
        JsonNode node = params.path("video");
        boolean enabled = hasPromptFields;
        if (node.path("enabled").isBoolean()) {
            enabled = node.path("enabled").asBoolean();
        }
        String model = textOrDefault(node.path("model"), props.defaultVideoModel());
        if (model == null || model.isBlank()) {
            if (isLocalOllamaRequest(params)) {
                enabled = false;
                model = "";
            } else {
                model = "sora-2";
            }
        }
        Integer durationSeconds = node.path("durationSeconds").isInt()
                ? Integer.valueOf(node.path("durationSeconds").asInt())
                : null;
        if (durationSeconds == null) {
            durationSeconds = props.defaultVideoDurationSeconds();
        }
        if (durationSeconds == null || durationSeconds <= 0) {
            durationSeconds = 4;
        }
        if (!List.of(4, 8, 12).contains(durationSeconds)) {
            durationSeconds = 4;
        }
        String resolution = textOrDefault(node.path("resolution"), props.defaultVideoResolution());
        if (resolution == null || resolution.isBlank()) {
            resolution = "1280x720";
        }
        String format = textOrDefault(node.path("format"), "mp4");
        return new VideoConfig(enabled, model, durationSeconds, resolution, format);
    }

    private boolean isLocalOllamaRequest(JsonNode params) {
        String provider = textOrNull(params.path("provider"));
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return "ollama".equals(normalized) || "local-openai".equals(normalized);
    }

    private MediaUpload generateImage(AiJobEntity job, String apiKey, ImageConfig config, String prompt) {
        OpenAiImageResult result = openAiClient.createImage(
                apiKey,
                new OpenAiImageRequest(config.model(), prompt, config.size(), config.quality(), config.style(), config.format())
        );
        String contentType = result.mimeType() == null || result.mimeType().isBlank() ? "image/png" : result.mimeType();
        String extension = resolveExtensionFromMimeType(contentType, config.format());
        String fileName = "ai-image-" + job.getJobId() + "-" + UUID.randomUUID() + "." + extension;
        UUID mediaId = mediaApiClient.directUpload(
                job.getUserId(),
                "card_image",
                contentType,
                fileName,
                result.data().length,
                new ByteArrayInputStream(result.data())
        );
        return new MediaUpload(mediaId, contentType, fileName);
    }

    private ObjectNode loadLatestContent(UUID jobId,
                                         UUID deckId,
                                         UUID cardId,
                                         String accessToken,
                                         ObjectNode fallback) {
        if (deckId == null || cardId == null || accessToken == null || accessToken.isBlank()) {
            return fallback;
        }
        try {
            CoreApiClient.CoreUserCardDetail detail = coreApiClient.getUserCard(deckId, cardId, accessToken);
            if (detail != null && detail.effectiveContent() != null && detail.effectiveContent().isObject()) {
                return detail.effectiveContent().deepCopy();
            }
        } catch (Exception ex) {
            LOGGER.warn("OpenAI failed to load latest card content jobId={} cardId={}", jobId, cardId, ex);
        }
        return fallback;
    }

    private MediaUpload generateVideo(AiJobEntity job, String apiKey, VideoConfig config, String prompt) {
        OpenAiVideoJob videoJob = openAiClient.createVideoJob(
                apiKey,
                new OpenAiVideoRequest(config.model(), prompt, config.durationSeconds(), config.resolution())
        );
        OpenAiVideoJob completed = waitForVideoCompletion(apiKey, videoJob);
        byte[] data = openAiClient.downloadVideoContent(apiKey, completed.id());
        String format = config.format() == null ? "mp4" : config.format().toLowerCase();
        String contentType = "gif".equals(format) ? "image/gif" : "video/mp4";
        String extension = "gif".equals(format) ? "gif" : "mp4";
        String fileName = "ai-video-" + job.getJobId() + "-" + UUID.randomUUID() + "." + extension;
        UUID mediaId = mediaApiClient.directUpload(
                job.getUserId(),
                "card_video",
                contentType,
                fileName,
                data.length,
                new ByteArrayInputStream(data)
        );
        return new MediaUpload(mediaId, contentType, fileName);
    }

    private OpenAiVideoJob waitForVideoCompletion(String apiKey, OpenAiVideoJob job) {
        if (job == null || job.id() == null || job.id().isBlank()) {
            throw new IllegalStateException("OpenAI video job id is missing");
        }
        OpenAiVideoJob current = job;
        Instant start = Instant.now();
        while (Duration.between(start, Instant.now()).compareTo(VIDEO_POLL_TIMEOUT) < 0) {
            if (current.isCompleted()) {
                return current;
            }
            if (current.isFailed()) {
                String error = current.error() == null ? "OpenAI video failed" : current.error();
                throw new IllegalStateException(error);
            }
            try {
                Thread.sleep(VIDEO_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Video generation interrupted");
            }
            current = openAiClient.getVideoJob(apiKey, job.id());
        }
        throw new IllegalStateException("Video generation timed out");
    }

    private String resolveExtensionFromMimeType(String mimeType, String fallbackFormat) {
        if (mimeType == null || mimeType.isBlank()) {
            return fallbackFormat != null && !fallbackFormat.isBlank() ? fallbackFormat : "png";
        }
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "video/mp4" -> "mp4";
            default -> "png";
        };
    }

    private List<TtsMapping> resolveTtsMappings(JsonNode ttsNode,
                                                List<String> textFields,
                                                List<String> audioFields,
                                                CoreTemplateResponse template) {
        List<TtsMapping> mappings = new java.util.ArrayList<>();
        JsonNode mappingNode = ttsNode.path("mappings");
        if (mappingNode.isArray()) {
            for (JsonNode node : mappingNode) {
                String source = node.path("sourceField").asText(null);
                String target = node.path("targetField").asText(null);
                if (source != null && target != null && textFields.contains(source) && audioFields.contains(target)) {
                    mappings.add(new TtsMapping(source, target));
                }
            }
        }
        if (!mappings.isEmpty()) {
            return mappings;
        }

        String defaultSource = resolveDefaultSourceField(template, textFields);
        for (String target : audioFields) {
            mappings.add(new TtsMapping(defaultSource, target));
        }
        return mappings;
    }

    private String resolveDefaultSourceField(CoreTemplateResponse template, List<String> textFields) {
        if (template == null || template.fields() == null || template.fields().isEmpty()) {
            return textFields.getFirst();
        }
        return template.fields().stream()
                .filter(this::isTextField)
                .sorted((a, b) -> {
                    int frontCompare = Boolean.compare(!a.isOnFront(), !b.isOnFront());
                    if (frontCompare != 0) {
                        return frontCompare;
                    }
                    Integer aOrder = a.orderIndex() == null ? Integer.MAX_VALUE : a.orderIndex();
                    Integer bOrder = b.orderIndex() == null ? Integer.MAX_VALUE : b.orderIndex();
                    return Integer.compare(aOrder, bOrder);
                })
                .map(CoreFieldTemplate::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(textFields.getFirst());
    }

    private String extractTextValue(JsonNode content, String field) {
        if (content == null || field == null) {
            return null;
        }
        JsonNode node = content.get(field);
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        return null;
    }

    private String sanitizeTtsText(String text) {
        if (text == null) {
            return null;
        }
        String sanitized = text;
        sanitized = sanitized.replaceAll("!\\[([^\\]]*)]\\([^)]*\\)", "$1");
        sanitized = sanitized.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
        sanitized = sanitized.replaceAll("https?://\\S+", "");
        sanitized = sanitized.replaceAll("<[^>]+>", " ");
        sanitized = sanitized.replaceAll("`{1,3}", "");
        sanitized = sanitized.replaceAll("[*_~#>]+", "");
        sanitized = sanitized.replaceAll("&nbsp;", " ");
        sanitized = sanitized.replaceAll("&amp;", "and");
        sanitized = MULTI_SPACE_PATTERN.matcher(sanitized).replaceAll(" ").trim();
        return sanitized.isBlank() ? null : sanitized;
    }

    private byte[] createSpeechWithRetry(AiJobEntity job,
                                         String apiKey,
                                         OpenAiSpeechRequest request,
                                         UUID cardId,
                                         String targetField) {
        JsonNode params = safeParams(job);
        boolean localProvider = isLocalOllamaRequest(params);
        int maxRetries = resolveTtsMaxRetries();
        long delayMs = resolveTtsRetryInitialDelayMs();
        long maxDelayMs = resolveTtsRetryMaxDelayMs();
        int attempts = 0;
        while (true) {
            throttleTtsRequests(localProvider);
            try {
                return openAiClient.createSpeech(apiKey, request);
            } catch (RestClientResponseException ex) {
                if (!isRetryableStatus(ex.getRawStatusCode())) {
                    throw ex;
                }
                if (attempts >= maxRetries) {
                    throw ex;
                }
                long waitMs = resolveRetryAfterMs(ex);
                if (waitMs <= 0) {
                    waitMs = delayMs;
                    delayMs = Math.min(delayMs * 2, maxDelayMs);
                } else {
                    delayMs = Math.min(Math.max(delayMs, waitMs), maxDelayMs);
                }
                LOGGER.warn("OpenAI TTS rate limited jobId={} cardId={} field={} status={} waitMs={}",
                        job.getJobId(),
                        cardId,
                        targetField,
                        ex.getRawStatusCode(),
                        waitMs);
                if (!sleepQuietly(waitMs)) {
                    throw new IllegalStateException("OpenAI TTS retry interrupted");
                }
                attempts++;
            } catch (RestClientException ex) {
                if (!OpenAiClient.isRetryableTransportFailure(ex) || attempts >= maxRetries) {
                    throw ex;
                }
                long waitMs = delayMs;
                delayMs = Math.min(delayMs * 2, maxDelayMs);
                LOGGER.warn("OpenAI TTS transport failure jobId={} cardId={} field={} waitMs={} message={}",
                        job.getJobId(),
                        cardId,
                        targetField,
                        waitMs,
                        summarizeError(ex));
                if (!sleepQuietly(waitMs)) {
                    throw new IllegalStateException("OpenAI TTS retry interrupted");
                }
                attempts++;
            }
        }
    }

    private void throttleTtsRequests(boolean localProvider) {
        int rpm = resolveTtsRequestsPerMinute(localProvider);
        if (rpm <= 0) {
            return;
        }
        long intervalMs = (long) Math.ceil(60000.0 / rpm);
        long sleepMs = 0L;
        long now = System.currentTimeMillis();
        synchronized (ttsThrottleLock) {
            if (nextTtsRequestAtMs > now) {
                sleepMs = nextTtsRequestAtMs - now;
                nextTtsRequestAtMs += intervalMs;
            } else {
                nextTtsRequestAtMs = now + intervalMs;
            }
        }
        if (sleepMs > 0 && !sleepQuietly(sleepMs)) {
            throw new IllegalStateException("OpenAI TTS throttling interrupted");
        }
    }

    private int resolveTtsRequestsPerMinute(boolean localProvider) {
        Integer rpm = localProvider ? props.localTtsRequestsPerMinute() : props.ttsRequestsPerMinute();
        if (rpm == null) {
            return localProvider ? 12 : DEFAULT_TTS_REQUESTS_PER_MINUTE;
        }
        return Math.max(rpm, 0);
    }

    private int resolveTtsMaxRetries() {
        Integer retries = props.ttsMaxRetries();
        if (retries == null) {
            return DEFAULT_TTS_MAX_RETRIES;
        }
        return Math.max(retries, 0);
    }

    private long resolveTtsRetryInitialDelayMs() {
        Long delay = props.ttsRetryInitialDelayMs();
        if (delay == null || delay < 0) {
            return DEFAULT_TTS_RETRY_INITIAL_DELAY_MS;
        }
        return delay;
    }

    private long resolveTtsRetryMaxDelayMs() {
        Long delay = props.ttsRetryMaxDelayMs();
        long fallback = DEFAULT_TTS_RETRY_MAX_DELAY_MS;
        if (delay == null || delay < 0) {
            delay = fallback;
        }
        long initial = resolveTtsRetryInitialDelayMs();
        return Math.max(delay, initial);
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private long resolveRetryAfterMs(RestClientResponseException ex) {
        if (ex == null) {
            return 0L;
        }
        Long headerMs = parseRetryAfterHeader(ex.getResponseHeaders());
        if (headerMs != null) {
            return headerMs;
        }
        Long bodyMs = parseRetryAfterMessage(ex.getResponseBodyAsString());
        if (bodyMs != null) {
            return bodyMs;
        }
        Long messageMs = parseRetryAfterMessage(ex.getMessage());
        return messageMs == null ? 0L : messageMs;
    }

    private Long parseRetryAfterHeader(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst("Retry-After");
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds >= 0) {
                return seconds * 1000L;
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            Instant retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed, Instant::from);
            long delta = retryAt.toEpochMilli() - System.currentTimeMillis();
            return Math.max(delta, 0L);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static Long parseRetryAfterMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = RETRY_IN_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(matcher.group(1));
            if (seconds < 0) {
                return null;
            }
            return Math.round(seconds * 1000.0);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean sleepQuietly(long delayMs) {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void appendCardError(Map<UUID, List<String>> cardErrors, UUID cardId, String field, String message) {
        if (cardErrors == null || cardId == null || message == null || message.isBlank()) {
            return;
        }
        cardErrors.computeIfAbsent(cardId, ignored -> new ArrayList<>()).add(formatFieldError(field, message));
    }

    private void appendErrors(Map<UUID, List<String>> cardErrors, UUID cardId, List<String> messages) {
        if (cardErrors == null || cardId == null || messages == null || messages.isEmpty()) {
            return;
        }
        cardErrors.computeIfAbsent(cardId, ignored -> new ArrayList<>()).addAll(messages);
    }

    private String formatFieldError(String field, String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return field == null || field.isBlank()
                ? message
                : field + ": " + message;
    }

    private AiJobStatus resolveFinalStatus(boolean hasPartialFailures) {
        return hasPartialFailures ? AiJobStatus.partial_success : AiJobStatus.completed;
    }

    private MediaApplyResult materializeDraftMediaResult(List<CoreUserCardResponse> createdCards,
                                                         DraftMediaPreparationResult draftResult) {
        if (draftResult == null) {
            return new MediaApplyResult(0, 0, 0);
        }
        Set<UUID> updatedCardIds = mapDraftIndexesToCardIds(createdCards, draftResult.updatedDraftIndexes());
        return new MediaApplyResult(
                updatedCardIds.size(),
                draftResult.imagesGenerated(),
                draftResult.videosGenerated(),
                updatedCardIds,
                mapDraftErrorsToCardIds(createdCards, draftResult.draftErrors())
        );
    }

    private TtsApplyResult materializeDraftTtsResult(List<CoreUserCardResponse> createdCards,
                                                     DraftTtsPreparationResult draftResult) {
        if (draftResult == null) {
            return new TtsApplyResult(0, 0, null, null);
        }
        Set<UUID> updatedCardIds = mapDraftIndexesToCardIds(createdCards, draftResult.updatedDraftIndexes());
        return new TtsApplyResult(
                draftResult.generated(),
                updatedCardIds.size(),
                draftResult.charsGenerated(),
                draftResult.model(),
                draftResult.error(),
                updatedCardIds,
                mapDraftErrorsToCardIds(createdCards, draftResult.draftErrors())
        );
    }

    private Set<UUID> mapDraftIndexesToCardIds(List<CoreUserCardResponse> createdCards,
                                               Set<Integer> indexes) {
        if (createdCards == null || createdCards.isEmpty() || indexes == null || indexes.isEmpty()) {
            return Set.of();
        }
        Set<UUID> cardIds = new LinkedHashSet<>();
        for (Integer index : indexes) {
            if (index == null || index < 0 || index >= createdCards.size()) {
                continue;
            }
            CoreUserCardResponse card = createdCards.get(index);
            if (card != null && card.userCardId() != null) {
                cardIds.add(card.userCardId());
            }
        }
        return cardIds;
    }

    private Map<UUID, List<String>> mapDraftErrorsToCardIds(List<CoreUserCardResponse> createdCards,
                                                            Map<Integer, List<String>> draftErrors) {
        if (createdCards == null || createdCards.isEmpty() || draftErrors == null || draftErrors.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> cardErrors = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : draftErrors.entrySet()) {
            Integer index = entry.getKey();
            if (index == null || index < 0 || index >= createdCards.size()) {
                continue;
            }
            CoreUserCardResponse card = createdCards.get(index);
            if (card == null || card.userCardId() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            cardErrors.computeIfAbsent(card.userCardId(), ignored -> new ArrayList<>()).addAll(entry.getValue());
        }
        return cardErrors;
    }

    private TtsApplyResult mergeTtsResults(TtsApplyResult left, TtsApplyResult right) {
        if (left == null) {
            return right == null ? new TtsApplyResult(0, 0, 0, null, null) : right;
        }
        if (right == null) {
            return left;
        }
        Set<UUID> updatedCardIds = new LinkedHashSet<>();
        updatedCardIds.addAll(left.updatedCardIds());
        updatedCardIds.addAll(right.updatedCardIds());
        Map<UUID, List<String>> cardErrors = new LinkedHashMap<>();
        mergeCardErrors(cardErrors, left.cardErrors());
        mergeCardErrors(cardErrors, right.cardErrors());
        return new TtsApplyResult(
                left.generated() + right.generated(),
                left.updatedCards() + right.updatedCards(),
                left.charsGenerated() + right.charsGenerated(),
                left.model() != null ? left.model() : right.model(),
                left.error() != null ? left.error() : right.error(),
                updatedCardIds,
                cardErrors
        );
    }

    private void mergeCardErrors(Map<UUID, List<String>> target, Map<UUID, List<String>> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        source.forEach((cardId, errors) -> {
            if (cardId == null || errors == null || errors.isEmpty()) {
                return;
            }
            target.computeIfAbsent(cardId, ignored -> new ArrayList<>()).addAll(errors);
        });
    }

    private ArrayNode buildGeneratedCardItems(List<CoreUserCardResponse> createdCards,
                                              List<CardDraft> drafts,
                                              List<String> allowedFields,
                                              TtsApplyResult ttsResult,
                                              MediaApplyResult mediaResult) {
        ArrayNode items = objectMapper.createArrayNode();
        int limit = Math.min(createdCards == null ? 0 : createdCards.size(), drafts == null ? 0 : drafts.size());
        for (int i = 0; i < limit; i++) {
            CoreUserCardResponse card = createdCards.get(i);
            CardDraft draft = drafts.get(i);
            if (card == null || card.userCardId() == null) {
                continue;
            }
            items.add(buildItemNode(
                    card.userCardId(),
                    extractCardPreview(card.effectiveContent(), allowedFields, draft == null ? null : draft.content()),
                    List.of("text"),
                    ttsResult.updatedCardIds().contains(card.userCardId()) ? List.of("tts") : List.of(),
                    mediaResult.updatedCardIds().contains(card.userCardId()) ? List.of("media") : List.of(),
                    mergeCardErrors(card.userCardId(), ttsResult.cardErrors(), mediaResult.cardErrors())
            ));
        }
        return items;
    }

    private ArrayNode buildEnhanceItems(List<CoreUserCardResponse> cards,
                                        MediaApplyResult mediaResult,
                                        TtsApplyResult ttsResult) {
        ArrayNode items = objectMapper.createArrayNode();
        if (cards == null || cards.isEmpty()) {
            return items;
        }
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.userCardId() == null) {
                continue;
            }
            items.add(buildItemNode(
                    card.userCardId(),
                    extractCardPreview(card.effectiveContent(), List.of(), null),
                    mediaResult.updatedCardIds().contains(card.userCardId()) ? List.of("content") : List.of(),
                    ttsResult.updatedCardIds().contains(card.userCardId()) ? List.of("tts") : List.of(),
                    List.of(),
                    mergeCardErrors(card.userCardId(), mediaResult.cardErrors(), ttsResult.cardErrors())
            ));
        }
        return items;
    }

    @SafeVarargs
    private final List<String> mergeCardErrors(UUID cardId, Map<UUID, List<String>>... errorMaps) {
        if (cardId == null || errorMaps == null || errorMaps.length == 0) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (Map<UUID, List<String>> errorMap : errorMaps) {
            if (errorMap == null || errorMap.isEmpty()) {
                continue;
            }
            List<String> values = errorMap.get(cardId);
            if (values != null && !values.isEmpty()) {
                errors.addAll(values);
            }
        }
        return errors;
    }

    private ObjectNode buildItemNode(UUID cardId,
                                     String preview,
                                     List<String> contentStages,
                                     List<String> ttsStages,
                                     List<String> mediaStages,
                                     List<String> errors) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("cardId", cardId.toString());
        if (preview != null && !preview.isBlank()) {
            item.put("preview", preview);
        }
        ArrayNode completedStages = item.putArray("completedStages");
        contentStages.forEach(completedStages::add);
        ttsStages.forEach(completedStages::add);
        mediaStages.forEach(completedStages::add);
        if (errors != null && !errors.isEmpty()) {
            ArrayNode errorNode = item.putArray("errors");
            errors.forEach(errorNode::add);
        }
        String status;
        if (errors != null && !errors.isEmpty() && completedStages.isEmpty()) {
            status = "failed";
        } else if (errors != null && !errors.isEmpty()) {
            status = "partial_success";
        } else if (!completedStages.isEmpty()) {
            status = "completed";
        } else {
            status = "skipped";
        }
        item.put("status", status);
        return item;
    }

    private String extractCardPreview(JsonNode primaryContent, List<String> preferredFields, JsonNode fallbackContent) {
        String preview = extractPreviewFromContent(primaryContent, preferredFields);
        if (preview != null) {
            return preview;
        }
        return extractPreviewFromContent(fallbackContent, preferredFields);
    }

    private String extractPreviewFromContent(JsonNode content, List<String> preferredFields) {
        if (content == null || !content.isObject() || content.isEmpty()) {
            return null;
        }
        if (preferredFields != null) {
            for (String field : preferredFields) {
                String value = extractPreviewValue(content.get(field));
                if (value != null) {
                    return value;
                }
            }
        }
        var iterator = content.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String value = extractPreviewValue(entry.getValue());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String extractPreviewValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.isEmpty()) {
                return null;
            }
            return text.length() > 120 ? text.substring(0, 120) + "..." : text;
        }
        return null;
    }

    private String summarizeError(Exception ex) {
        if (ex == null) {
            return "Unknown error";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private record TtsMapping(String sourceField, String targetField) {
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String resolveAudioContentType(String format) {
        if ("wav".equalsIgnoreCase(format)) {
            return "audio/wav";
        }
        if ("ogg".equalsIgnoreCase(format)) {
            return "audio/ogg";
        }
        return "audio/mpeg";
    }

    private record CredentialSelection(AiProviderCredentialEntity credential, String apiKey) {
    }
}
