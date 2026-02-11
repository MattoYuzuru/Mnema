package app.mnema.ai.provider.gemini;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.client.core.CoreApiClient.CoreFieldTemplate;
import app.mnema.ai.client.core.CoreApiClient.CorePublicDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreTemplateResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardPage;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CreateCardRequestPayload;
import app.mnema.ai.client.core.CoreApiClient.UpdateUserCardRequest;
import app.mnema.ai.client.media.MediaApiClient;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AudioChunkingService;
import app.mnema.ai.service.AiImportContentService;
import app.mnema.ai.service.AiJobProcessingResult;
import app.mnema.ai.service.AiProviderProcessor;
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
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiJobProcessor implements AiProviderProcessor {

    private static final String PROVIDER = "gemini";
    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiJobProcessor.class);
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
    private static final int DEFAULT_PCM_SAMPLE_RATE = 24000;
    private static final Pattern PCM_RATE_PATTERN = Pattern.compile("rate=([0-9]+)");
    private static final Pattern RETRY_IN_PATTERN = Pattern.compile("retry in\\s*([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_TTS_REQUESTS_PER_MINUTE = 10;
    private static final int DEFAULT_TTS_MAX_RETRIES = 5;
    private static final long DEFAULT_TTS_RETRY_INITIAL_DELAY_MS = 2000L;
    private static final long DEFAULT_TTS_RETRY_MAX_DELAY_MS = 30000L;
    private static final int DEFAULT_VISION_MAX_OUTPUT_TOKENS = 800;
    private static final Map<String, String> GEMINI_VOICES = Map.ofEntries(
            Map.entry("zephyr", "Zephyr"),
            Map.entry("puck", "Puck"),
            Map.entry("charon", "Charon"),
            Map.entry("kore", "Kore"),
            Map.entry("fenrir", "Fenrir"),
            Map.entry("leda", "Leda"),
            Map.entry("orus", "Orus"),
            Map.entry("aoede", "Aoede"),
            Map.entry("callirrhoe", "Callirrhoe"),
            Map.entry("autonoe", "Autonoe"),
            Map.entry("enceladus", "Enceladus"),
            Map.entry("iapetus", "Iapetus"),
            Map.entry("umbriel", "Umbriel"),
            Map.entry("algieba", "Algieba"),
            Map.entry("despina", "Despina"),
            Map.entry("erinome", "Erinome"),
            Map.entry("algenib", "Algenib"),
            Map.entry("rasalgethi", "Rasalgethi"),
            Map.entry("laomedeia", "Laomedeia"),
            Map.entry("achernar", "Achernar"),
            Map.entry("alnilam", "Alnilam"),
            Map.entry("schedar", "Schedar"),
            Map.entry("gacrux", "Gacrux"),
            Map.entry("pulcherrima", "Pulcherrima"),
            Map.entry("achird", "Achird"),
            Map.entry("zubenelgenubi", "Zubenelgenubi"),
            Map.entry("vindemiatrix", "Vindemiatrix"),
            Map.entry("sadachbia", "Sadachbia"),
            Map.entry("sadaltager", "Sadaltager"),
            Map.entry("sulafat", "Sulafat")
    );

    private final GeminiClient geminiClient;
    private final GeminiProps props;
    private final SecretVault secretVault;
    private final AiProviderCredentialRepository credentialRepository;
    private final MediaApiClient mediaApiClient;
    private final AiImportContentService importContentService;
    private final AudioChunkingService audioChunkingService;
    private final CoreApiClient coreApiClient;
    private final ObjectMapper objectMapper;
    private final AnkiTemplateSupport ankiSupport;
    private final int maxImportChars;
    private final Object ttsThrottleLock = new Object();
    private long nextTtsRequestAtMs = 0L;

    public GeminiJobProcessor(GeminiClient geminiClient,
                              GeminiProps props,
                               SecretVault secretVault,
                               AiProviderCredentialRepository credentialRepository,
                               MediaApiClient mediaApiClient,
                               AiImportContentService importContentService,
                               AudioChunkingService audioChunkingService,
                               CoreApiClient coreApiClient,
                               ObjectMapper objectMapper,
                               @Value("${app.ai.import.max-chars:200000}") int maxImportChars) {
        this.geminiClient = geminiClient;
        this.props = props;
        this.secretVault = secretVault;
        this.credentialRepository = credentialRepository;
        this.mediaApiClient = mediaApiClient;
        this.importContentService = importContentService;
        this.audioChunkingService = audioChunkingService;
        this.coreApiClient = coreApiClient;
        this.objectMapper = objectMapper;
        this.ankiSupport = new AnkiTemplateSupport(objectMapper);
        this.maxImportChars = Math.max(maxImportChars, 1000);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public AiJobProcessingResult process(AiJobEntity job) {
        AiProviderCredentialEntity credential = resolveCredential(job);
        String apiKey = decryptSecret(credential);
        credential.setLastUsedAt(Instant.now());
        credential.setUpdatedAt(Instant.now());
        credentialRepository.save(credential);

        if (job.getType() == AiJobType.tts) {
            return handleTts(job, apiKey);
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
        if (MODE_ENHANCE.equalsIgnoreCase(mode) && hasAction(params, "audit")) {
            return handleAudit(job, apiKey, params);
        }
        if (MODE_MISSING_AUDIO.equalsIgnoreCase(mode)) {
            return handleMissingAudio(job, apiKey, params);
        }
        if (MODE_MISSING_FIELDS.equalsIgnoreCase(mode)) {
            return handleMissingFields(job, apiKey, params);
        }
        if (MODE_GENERATE_CARDS.equalsIgnoreCase(mode)) {
            return handleGenerateCards(job, apiKey, params);
        }
        return handleFreeformText(job, apiKey, params);
    }

    private AiJobProcessingResult handleImportPreview(AiJobEntity job, String apiKey, JsonNode params) {
        AiImportContentService.ImportTextPayload payload = loadImportPayload(job, apiKey, params);

        String prompt = buildImportPreviewPrompt(payload, params);
        JsonNode responseSchema = buildImportPreviewSchema(payload.maxRecommendedCards());
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        GeminiResponseResult response = geminiClient.createResponse(
                apiKey,
                new GeminiResponseRequest(model, prompt, maxOutputTokens, "application/json", responseSchema)
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
    }

    private AiJobProcessingResult handleImportGenerate(AiJobEntity job, String apiKey, JsonNode params) {
        AiImportContentService.ImportTextPayload payload = loadImportPayload(job, apiKey, params);

        ObjectNode updatedParams = params.isObject()
                ? ((ObjectNode) params).deepCopy()
                : objectMapper.createObjectNode();
        updatedParams.put("mode", MODE_GENERATE_CARDS);
        updatedParams.put("countLimit", MAX_IMPORT_CARDS);
        int requested = params.path("count").asInt(10);
        int total = Math.min(Math.max(requested, 1), MAX_IMPORT_CARDS);
        int batchSize = resolveImportBatchSize(params);
        List<String> items = ImportItemExtractor.extractItems(payload.text());
        if (!items.isEmpty()) {
            int available = Math.min(items.size(), MAX_IMPORT_CARDS);
            total = Math.min(total, available);
            List<String> requestedItems = items.subList(0, total);
            if (total <= batchSize) {
                updatedParams.put("count", total);
                updatedParams.put("input", buildImportGenerateItemsPrompt(requestedItems, params, payload.truncated()));
                return handleGenerateCards(job, apiKey, updatedParams);
            }
            return handleGenerateCardsBatchedForItems(job, apiKey, updatedParams, requestedItems, batchSize, params, payload.truncated());
        }
        updatedParams.put("input", buildImportGeneratePrompt(payload, params));
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
        int remaining = totalCount;
        int totalCreated = 0;
        int totalImages = 0;
        int totalTts = 0;
        String ttsError = null;
        Integer tokensIn = null;
        Integer tokensOut = null;
        BigDecimal cost = BigDecimal.ZERO;
        String model = null;
        String templateId = null;
        JsonNode fieldsNode = null;

        while (remaining > 0) {
            int count = Math.min(batchSize, remaining);
            ObjectNode batchParams = baseParams.deepCopy();
            batchParams.put("count", count);
            AiJobProcessingResult batchResult = handleGenerateCards(job, apiKey, batchParams);
            model = batchResult.model();
            if (batchResult.tokensIn() != null) {
                tokensIn = (tokensIn == null ? 0 : tokensIn) + batchResult.tokensIn();
            }
            if (batchResult.tokensOut() != null) {
                tokensOut = (tokensOut == null ? 0 : tokensOut) + batchResult.tokensOut();
            }
            if (batchResult.costEstimate() != null) {
                cost = cost.add(batchResult.costEstimate());
            }
            JsonNode summary = batchResult.resultSummary();
            if (summary != null && summary.isObject()) {
                if (templateId == null && summary.hasNonNull("templateId")) {
                    templateId = summary.get("templateId").asText();
                }
                if (fieldsNode == null && summary.has("fields")) {
                    fieldsNode = summary.get("fields");
                }
                totalCreated += summary.path("createdCards").asInt(0);
                totalImages += summary.path("imagesGenerated").asInt(0);
                totalTts += summary.path("ttsGenerated").asInt(0);
                if (ttsError == null && summary.hasNonNull("ttsError")) {
                    ttsError = summary.get("ttsError").asText();
                }
            }
            remaining -= count;
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        if (templateId != null) {
            summary.put("templateId", templateId);
        }
        summary.put("requestedCards", totalCount);
        summary.put("createdCards", totalCreated);
        if (totalImages > 0) {
            summary.put("imagesGenerated", totalImages);
        }
        if (totalTts > 0) {
            summary.put("ttsGenerated", totalTts);
        }
        if (ttsError != null) {
            summary.put("ttsError", ttsError);
        }
        if (fieldsNode != null) {
            summary.set("fields", fieldsNode);
        }

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                model,
                tokensIn,
                tokensOut,
                cost,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleGenerateCardsBatchedForItems(AiJobEntity job,
                                                                     String apiKey,
                                                                     ObjectNode baseParams,
                                                                     List<String> items,
                                                                     int batchSize,
                                                                     JsonNode params,
                                                                     boolean truncated) {
        int remaining = items.size();
        int totalCreated = 0;
        int totalImages = 0;
        int totalTts = 0;
        String ttsError = null;
        Integer tokensIn = null;
        Integer tokensOut = null;
        BigDecimal cost = BigDecimal.ZERO;
        String model = null;
        String templateId = null;
        JsonNode fieldsNode = null;
        int offset = 0;

        while (remaining > 0) {
            int count = Math.min(batchSize, remaining);
            List<String> batchItems = items.subList(offset, offset + count);
            ObjectNode batchParams = baseParams.deepCopy();
            batchParams.put("count", count);
            batchParams.put("input", buildImportGenerateItemsPrompt(batchItems, params, truncated));
            AiJobProcessingResult batchResult = handleGenerateCards(job, apiKey, batchParams);
            model = batchResult.model();
            if (batchResult.tokensIn() != null) {
                tokensIn = (tokensIn == null ? 0 : tokensIn) + batchResult.tokensIn();
            }
            if (batchResult.tokensOut() != null) {
                tokensOut = (tokensOut == null ? 0 : tokensOut) + batchResult.tokensOut();
            }
            if (batchResult.costEstimate() != null) {
                cost = cost.add(batchResult.costEstimate());
            }
            JsonNode summary = batchResult.resultSummary();
            if (summary != null && summary.isObject()) {
                if (templateId == null && summary.hasNonNull("templateId")) {
                    templateId = summary.get("templateId").asText();
                }
                if (fieldsNode == null && summary.has("fields")) {
                    fieldsNode = summary.get("fields");
                }
                totalCreated += summary.path("createdCards").asInt(0);
                totalImages += summary.path("imagesGenerated").asInt(0);
                totalTts += summary.path("ttsGenerated").asInt(0);
                if (ttsError == null && summary.hasNonNull("ttsError")) {
                    ttsError = summary.get("ttsError").asText();
                }
            }
            remaining -= count;
            offset += count;
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        if (templateId != null) {
            summary.put("templateId", templateId);
        }
        summary.put("requestedCards", items.size());
        summary.put("createdCards", totalCreated);
        if (totalImages > 0) {
            summary.put("imagesGenerated", totalImages);
        }
        if (totalTts > 0) {
            summary.put("ttsGenerated", totalTts);
        }
        if (ttsError != null) {
            summary.put("ttsError", ttsError);
        }
        if (fieldsNode != null) {
            summary.set("fields", fieldsNode);
        }

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                model,
                tokensIn,
                tokensOut,
                cost,
                job.getInputHash()
        );
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

    private String buildImportGenerateItemsPrompt(List<String> items, JsonNode params, boolean truncated) {
        StringBuilder builder = new StringBuilder();
        builder.append("Items to convert into flashcards (use each item exactly once):");
        for (int i = 0; i < items.size(); i++) {
            builder.append("\n").append(i + 1).append(". ").append(items.get(i));
        }
        builder.append("\nCreate one card per item. Use the item text verbatim in the most relevant field.");
        builder.append(" Do not invent new items or repeat any item.");
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

    private String buildImportGeneratePrompt(AiImportContentService.ImportTextPayload payload, JsonNode params) {
        StringBuilder builder = new StringBuilder();
        builder.append("Source material:\n").append(payload.text()).append("\n\n");
        builder.append("Create flashcards from this material. If a selected field is missing, infer or create a helpful value.");
        String language = textOrNull(params.path("language"));
        if (language != null) {
            builder.append(" Language hint: ").append(language).append(".");
        }
        String instructions = textOrNull(params.path("instructions"));
        if (instructions != null) {
            builder.append(" User instructions: ").append(instructions).append(".");
        }
        if (payload.truncated()) {
            builder.append(" The source may be truncated.");
        }
        return builder.toString().trim();
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
        JsonNode responseSchema = buildVisionSchema();
        GeminiResponseResult response = geminiClient.createResponseWithInlineData(
                apiKey,
                new GeminiResponseRequest(model, prompt, DEFAULT_VISION_MAX_OUTPUT_TOKENS, "application/json", responseSchema),
                source.bytes(),
                source.mimeType()
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
        String sttLanguage = resolveSttLanguage(params, language);
        StringBuilder transcript = new StringBuilder();
        boolean truncated = false;
        for (AudioChunkingService.AudioChunk chunk : chunking.chunks()) {
            String prompt = buildAudioPrompt(params);
            GeminiResponseResult response = geminiClient.createResponseWithInlineData(
                    apiKey,
                    new GeminiResponseRequest(model, prompt, DEFAULT_VISION_MAX_OUTPUT_TOKENS, "application/json", buildAudioSchema()),
                    chunk.bytes(),
                    chunk.mimeType()
            );
            JsonNode parsed = parseJsonResponse(response.outputText());
            String chunkText = textOrDefault(parsed.path("text"), "");
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

    private String buildAudioPrompt(JsonNode params) {
        StringBuilder builder = new StringBuilder();
        builder.append("Transcribe the audio to plain text. ");
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
        return schema;
    }

    private JsonNode buildAudioSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string");
        schema.putArray("required").add("text");
        return schema;
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
        return "gemini-2.0-flash";
    }

    private String resolveSttLanguage(JsonNode params, String fallback) {
        String language = textOrNull(params.path("stt").path("language"));
        if (language != null && !language.isBlank()) {
            return language.trim();
        }
        return fallback;
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
        return schema;
    }

    private int resolveEstimatedCount(JsonNode node, int maxCards) {
        int count = node != null && node.isInt() ? node.asInt() : 1;
        if (count < 1) {
            count = 1;
        }
        return count;
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

        GeminiResponseResult response = geminiClient.createResponse(
                apiKey,
                new GeminiResponseRequest(model, input, maxOutputTokens, null, null)
        );

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
        var allowedFields = resolveAllowedFields(params, template);
        if (allowedFields.isEmpty()) {
            throw new IllegalStateException("No supported fields to generate");
        }
        Map<String, String> fieldTypes = resolveFieldTypes(template);

        String userPrompt = extractTextParam(params, "input", "prompt", "text");
        String prompt = buildCardsPrompt(userPrompt, template, publicDeck, allowedFields, count, job.getDeckId(), job.getUserAccessToken());
        JsonNode responseSchema = buildCardsSchema(allowedFields, count);
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        GeminiResponseResult response = geminiClient.createResponse(
                apiKey,
                new GeminiResponseRequest(model, prompt, maxOutputTokens, "application/json", responseSchema)
        );

        JsonNode parsed = parseJsonResponse(response.outputText());
        List<CardDraft> drafts = buildCardDrafts(parsed, allowedFields, template, fieldTypes);
        List<CreateCardRequestPayload> limitedRequests = drafts.stream()
                .limit(count)
                .map(draft -> new CreateCardRequestPayload(draft.content(), null, null, null, null, null))
                .toList();

        List<CoreUserCardResponse> createdCards = coreApiClient.addCards(
                job.getDeckId(),
                limitedRequests,
                accessToken,
                job.getJobId()
        );
        ImageConfig imageConfig = resolveImageConfig(params, true);
        MediaApplyResult mediaResult = applyMediaPromptsToNewCards(job, apiKey, accessToken, template, createdCards, drafts, fieldTypes, imageConfig, updateScope);
        TtsApplyResult ttsResult;
        try {
            ttsResult = applyTtsIfNeeded(job, apiKey, params, createdCards, template, updateScope);
        } catch (Exception ex) {
            ttsResult = new TtsApplyResult(0, 0, null, summarizeError(ex));
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("templateId", publicDeck.templateId().toString());
        summary.put("requestedCards", count);
        summary.put("createdCards", limitedRequests.size());
        if (mediaResult.imagesGenerated() > 0) {
            summary.put("imagesGenerated", mediaResult.imagesGenerated());
        }
        if (ttsResult.generated() > 0) {
            summary.put("ttsGenerated", ttsResult.generated());
        }
        if (ttsResult.error() != null) {
            summary.put("ttsError", ttsResult.error());
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        for (String field : allowedFields) {
            fieldsNode.add(field);
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
    }

    private AiJobProcessingResult handleMissingFields(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for missing field generation");
        }
        String accessToken = job.getUserAccessToken();
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

        java.util.LinkedHashSet<String> targetFields = new java.util.LinkedHashSet<>();
        targetFields.addAll(promptFields);
        targetFields.addAll(targetAudioFields);
        if (targetFields.isEmpty()) {
            throw new IllegalStateException("No supported fields to generate");
        }

        MissingCardSelection selection = selectMissingCards(job.getDeckId(), targetFields, params, accessToken);
        List<CoreUserCardResponse> missingCards = selection.cards();
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

        List<CoreUserCardResponse> promptCards = promptFields.isEmpty()
                ? List.of()
                : filterCardsForPrompt(missingCards, promptFields, fieldTypes, selection.allowedFieldsByCard());

        GeminiResponseResult response = null;
        MediaApplyResult mediaResult = new MediaApplyResult(0, 0);
        if (!promptCards.isEmpty()) {
            String userPrompt = extractTextParam(params, "input", "prompt", "notes");
            String prompt = buildMissingFieldsPrompt(userPrompt, template, publicDeck, promptFields, promptCards, job.getDeckId(), accessToken);
            JsonNode responseSchema = buildMissingFieldsSchema(promptFields);
            String model = textOrDefault(params.path("model"), props.defaultModel());
            Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                    ? params.path("maxOutputTokens").asInt()
                    : null;

            response = geminiClient.createResponse(
                    apiKey,
                    new GeminiResponseRequest(model, prompt, maxOutputTokens, "application/json", responseSchema)
            );

            JsonNode parsed = parseJsonResponse(response.outputText());
            List<MissingFieldUpdate> updates = parseMissingFieldUpdates(parsed, promptFields);
            ImageConfig imageConfig = resolveImageConfig(params, !promptFields.isEmpty());
            mediaResult = applyMissingFieldUpdates(job, apiKey, accessToken, template, promptCards, updates, fieldTypes, selection.allowedFieldsByCard(), imageConfig, updateScope);
        }

        TtsApplyResult ttsResult = new TtsApplyResult(0, 0, null, null);
        String ttsError = null;
        if (!targetAudioFields.isEmpty()) {
            if (!params.path("tts").path("enabled").asBoolean(false)) {
                ttsError = "TTS settings are required for audio fields";
            } else {
                List<CoreUserCardResponse> audioCards = filterCardsForAudio(missingCards, targetAudioFields, selection.allowedFieldsByCard());
                if (!audioCards.isEmpty()) {
                    ttsResult = applyTtsForMissingAudio(job, apiKey, params, audioCards, template, targetAudioFields, updateScope);
                    if (ttsError == null && ttsResult.error() != null) {
                        ttsError = ttsResult.error();
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
        if (!targetAudioFields.isEmpty()) {
            summary.put("ttsGenerated", ttsResult.generated());
            summary.put("ttsUpdatedCards", ttsResult.updatedCards());
            if (ttsError != null) {
                summary.put("ttsError", ttsError);
            }
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        targetFields.forEach(fieldsNode::add);

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response == null ? null : response.model(),
                response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash()
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
        int limit = resolveLimit(params.path("limit"));
        List<CoreUserCardResponse> missingCards = coreApiClient.getMissingFieldCards(
                job.getDeckId(),
                new CoreApiClient.MissingFieldCardsRequest(targetFields, limit, null),
                accessToken
        );
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

        TtsApplyResult ttsResult;
        String ttsError = null;
        try {
            ttsResult = applyTtsForMissingAudio(job, apiKey, params, missingCards, template, targetFields, updateScope);
        } catch (Exception ex) {
            ttsResult = new TtsApplyResult(0, 0, null, null);
            ttsError = summarizeError(ex);
        }
        if (ttsError == null && ttsResult.error() != null) {
            ttsError = ttsResult.error();
        }

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_MISSING_AUDIO);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("updatedCards", ttsResult.updatedCards());
        summary.put("ttsGenerated", ttsResult.generated());
        summary.put("candidates", missingCards.size());
        if (ttsError != null) {
            summary.put("ttsError", ttsError);
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        targetFields.forEach(fieldsNode::add);

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                ttsResult.model(),
                null,
                null,
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleAudit(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for audit");
        }
        String accessToken = job.getUserAccessToken();
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
        String prompt = buildAuditPrompt(params, template, publicDeck, targetFields, cards, analysis);
        JsonNode responseSchema = buildAuditSchema();
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        GeminiResponseResult response = geminiClient.createResponse(
                apiKey,
                new GeminiResponseRequest(model, prompt, maxOutputTokens, "application/json", responseSchema)
        );

        JsonNode parsed = parseJsonResponse(response.outputText());
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_AUDIT);
        summary.put("deckId", job.getDeckId().toString());
        summary.set("auditStats", analysis.summary());
        summary.set("auditIssues", analysis.issues());
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

        String prompt = buildCardAuditPrompt(publicDeck, template, card);
        JsonNode responseSchema = buildCardAuditSchema();
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        GeminiResponseResult response = geminiClient.createResponse(
                apiKey,
                new GeminiResponseRequest(model, prompt, maxOutputTokens, "application/json", responseSchema)
        );

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

        GeminiResponseResult response = null;
        MediaApplyResult mediaResult = new MediaApplyResult(0, 0);
        if (!promptFields.isEmpty()) {
            String prompt = buildCardMissingFieldsPrompt(publicDeck, template, card, promptFields);
            JsonNode responseSchema = buildCardMissingFieldsSchema(promptFields);
            String model = textOrDefault(params.path("model"), props.defaultModel());
            Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                    ? params.path("maxOutputTokens").asInt()
                    : null;

            response = geminiClient.createResponse(
                    apiKey,
                    new GeminiResponseRequest(model, prompt, maxOutputTokens, "application/json", responseSchema)
            );

            JsonNode parsed = parseJsonResponse(response.outputText());
            ImageConfig imageConfig = resolveImageConfig(params, true);
            mediaResult = applyCardMissingFieldUpdate(job, apiKey, accessToken, template, card, parsed, promptFields, fieldTypes, imageConfig, updateScope);
        }

        TtsApplyResult ttsResult = new TtsApplyResult(0, 0, null, null);
        String ttsError = null;
        if (!targetAudioFields.isEmpty()) {
            if (!params.path("tts").path("enabled").asBoolean(false)) {
                ttsError = "TTS settings are required for audio fields";
            } else {
                CoreUserCardResponse cardResponse = new CoreUserCardResponse(
                        card.userCardId(),
                        card.publicCardId(),
                        card.isCustom(),
                        card.effectiveContent()
                );
                List<CoreUserCardResponse> audioCards = filterCardsForAudio(List.of(cardResponse), targetAudioFields, Map.of());
                if (!audioCards.isEmpty()) {
                    ttsResult = applyTtsForMissingAudio(job, apiKey, params, audioCards, template, targetAudioFields, updateScope);
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
        if (!targetAudioFields.isEmpty()) {
            summary.put("ttsGenerated", ttsResult.generated());
            summary.put("ttsUpdatedCards", ttsResult.updatedCards());
            if (ttsError != null) {
                summary.put("ttsError", ttsError);
            }
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        java.util.LinkedHashSet<String> allFields = new java.util.LinkedHashSet<>();
        allFields.addAll(promptFields);
        allFields.addAll(targetAudioFields);
        allFields.forEach(fieldsNode::add);

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response == null ? null : response.model(),
                response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash()
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
        if (missingTargets.isEmpty()) {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("mode", MODE_CARD_MISSING_AUDIO);
            summary.put("deckId", job.getDeckId().toString());
            summary.put("cardId", cardId.toString());
            summary.put("updatedCards", 0);
            summary.put("ttsGenerated", 0);
            ArrayNode fieldsNode = summary.putArray("fields");
            targetFields.forEach(fieldsNode::add);
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
                    card.userCardId(),
                    card.publicCardId(),
                    card.isCustom(),
                    card.effectiveContent()
            );
            ttsResult = applyTtsForMissingAudio(job, apiKey, params, List.of(cardResponse), template, missingTargets, updateScope);
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
        if (ttsError != null) {
            summary.put("ttsError", ttsError);
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        missingTargets.forEach(fieldsNode::add);

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                ttsResult.model(),
                null,
                null,
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleTts(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String text = extractTextParam(params, "text", "input", "prompt");
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("TTS text is required");
        }
        String model = resolveTtsModel(params.path("model"));
        String voice = resolveTtsVoice(params.path("voice"));
        String mimeType = resolveMimeType(params.path("mimeType"), params.path("format"));

        GeminiResponseParser.AudioResult audio = createSpeechWithRetry(
                job,
                apiKey,
                new GeminiSpeechRequest(model, text, voice, mimeType),
                null,
                null
        );

        NormalizedAudio normalized = normalizeGeminiAudio(audio, mimeType);
        String contentType = normalized.mimeType();
        String extension = resolveAudioExtension(contentType);
        String fileName = "ai-tts-" + job.getJobId() + "." + extension;
        UUID mediaId = mediaApiClient.directUpload(
                job.getUserId(),
                "card_audio",
                contentType,
                fileName,
                normalized.data().length,
                new ByteArrayInputStream(normalized.data())
        );

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mediaId", mediaId.toString());
        summary.put("contentType", contentType);
        summary.put("fileName", fileName);
        summary.put("model", model);
        summary.put("voice", voice);

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

    private AiProviderCredentialEntity resolveCredential(AiJobEntity job) {
        JsonNode params = safeParams(job);
        UUID credentialId = parseUuid(params.path("providerCredentialId").asText(null));
        if (credentialId != null) {
            return credentialRepository.findByIdAndUserId(credentialId, job.getUserId())
                    .filter(entity -> entity.getStatus() == AiProviderStatus.active)
                    .orElseThrow(() -> new IllegalStateException("Provider credential not found"));
        }
        throw new IllegalStateException("Provider credential is required");
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
        return new String(raw, StandardCharsets.UTF_8);
    }

    private JsonNode safeParams(AiJobEntity job) {
        return job.getParamsJson() == null ? objectMapper.createObjectNode() : job.getParamsJson();
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

    private String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
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
        int requested = params.path("count").asInt(10);
        if (requested < 1) {
            requested = 1;
        }
        int limit = params.path("countLimit").asInt(MAX_CARDS);
        if (limit < 1) {
            limit = 1;
        }
        return Math.min(requested, limit);
    }

    private int resolveImportBatchSize(JsonNode params) {
        int fields = params.path("fields").isArray() ? params.path("fields").size() : 0;
        int batchSize = fields > 0 ? 200 / fields : MAX_IMPORT_BATCH;
        if (batchSize < MIN_IMPORT_BATCH) {
            batchSize = MIN_IMPORT_BATCH;
        }
        if (batchSize > MAX_IMPORT_BATCH) {
            batchSize = MAX_IMPORT_BATCH;
        }
        return batchSize;
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
        List<String> allowed = resolveTemplateFields(template);
        if (requested.isEmpty()) {
            return resolveTextTemplateFields(template);
        }
        return requested.stream()
                .filter(allowed::contains)
                .toList();
    }

    private List<String> resolveAudioTargetFields(JsonNode params, List<String> audioFields) {
        List<String> requested = extractRequestedFields(params);
        if (requested.isEmpty()) {
            return audioFields;
        }
        return requested.stream()
                .filter(audioFields::contains)
                .toList();
    }

    private List<String> extractRequestedFields(JsonNode params) {
        List<String> requested = extractStringArray(params.path("fields"));
        if (!requested.isEmpty()) {
            return requested;
        }
        return new java.util.ArrayList<>(extractFieldLimits(params).keySet());
    }

    private Map<String, Integer> extractFieldLimits(JsonNode params) {
        JsonNode limitsNode = params.path("fieldLimits");
        if (!limitsNode.isArray()) {
            return Map.of();
        }
        Map<String, Integer> limits = new java.util.LinkedHashMap<>();
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

    private List<String> resolveTemplateFields(CoreTemplateResponse template) {
        if (template == null || template.fields() == null) {
            return List.of();
        }
        return template.fields().stream()
                .filter(this::isPromptField)
                .map(CoreFieldTemplate::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private List<String> resolveTextTemplateFields(CoreTemplateResponse template) {
        if (template == null || template.fields() == null) {
            return List.of();
        }
        return template.fields().stream()
                .filter(this::isTextField)
                .map(CoreFieldTemplate::name)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private boolean isTextField(CoreFieldTemplate field) {
        if (field == null) {
            return false;
        }
        String type = field.fieldType();
        return "text".equals(type) || "markdown".equals(type) || "rich_text".equals(type) || "cloze".equals(type);
    }

    private boolean isPromptField(CoreFieldTemplate field) {
        if (field == null || field.fieldType() == null) {
            return false;
        }
        return switch (field.fieldType()) {
            case "text", "rich_text", "markdown", "cloze", "image" -> true;
            default -> false;
        };
    }

    private Map<String, String> resolveFieldTypes(CoreTemplateResponse template) {
        if (template == null || template.fields() == null) {
            return Map.of();
        }
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (CoreFieldTemplate field : template.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) {
                continue;
            }
            map.put(field.name(), field.fieldType());
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

    private boolean hasFieldType(CoreTemplateResponse template, List<String> fields, String fieldType) {
        if (template == null || template.fields() == null) {
            return false;
        }
        var target = new java.util.HashSet<>(fields);
        for (CoreFieldTemplate field : template.fields()) {
            if (field == null || field.name() == null) {
                continue;
            }
            if (!target.contains(field.name())) {
                continue;
            }
            if (fieldType.equals(field.fieldType())) {
                return true;
            }
        }
        return false;
    }

    private String extractTextParam(JsonNode params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode node = params.get(key);
            if (node != null && node.isTextual()) {
                String value = node.asText().trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
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

    private String buildCardsPrompt(String userPrompt,
                                    CoreTemplateResponse template,
                                    CorePublicDeckResponse publicDeck,
                                    List<String> fields,
                                    int count,
                                    UUID deckId,
                                    String accessToken) {
        StringBuilder builder = new StringBuilder();
        builder.append("Generate ").append(count).append(" flashcards in JSON format. ");
        builder.append("Return an object with a 'cards' array. Each card must have a 'fields' object. ");
        builder.append("The fields object must contain exactly these keys: ").append(String.join(", ", fields)).append(". ");
        builder.append("Do not include any extra keys. ");
        builder.append("If a field is unknown or not applicable, return an empty string for that field. ");
        if (hasFieldType(template, fields, "image")) {
            builder.append("For image fields, return a short visual prompt describing the image (not a URL). ");
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
            String fieldHints = buildFieldHints(template, fields);
            if (!fieldHints.isBlank()) {
                builder.append("Field hints: ").append(fieldHints).append(". ");
            }
            String profile = formatAiProfile(template.aiProfile());
            if (!profile.isBlank()) {
                builder.append("Template AI profile: ").append(profile).append(". ");
            }
        }

        String examples = buildFewShotExamples(deckId, accessToken, fields);
        if (!examples.isBlank()) {
            builder.append("Examples from existing cards: ").append(examples).append(". ");
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            builder.append("User instructions: ").append(userPrompt.trim());
        }
        return builder.toString().trim();
    }

    private String buildMissingFieldsPrompt(String userPrompt,
                                            CoreTemplateResponse template,
                                            CorePublicDeckResponse publicDeck,
                                            List<String> targetFields,
                                            List<CoreUserCardResponse> cards,
                                            UUID deckId,
                                            String accessToken) {
        StringBuilder builder = new StringBuilder();
        builder.append("Fill missing fields for existing flashcards. ");
        builder.append("Return JSON with an 'updates' array of {userCardId, fields}. ");
        builder.append("Only include fields from: ").append(String.join(", ", targetFields)).append(". ");
        builder.append("Do not change fields that already have values. ");
        if (hasFieldType(template, targetFields, "image")) {
            builder.append("For image fields, return a short visual prompt describing the image (not a URL). ");
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
                                                      String updateScope) {
        if (updates.isEmpty()) {
            return new MediaApplyResult(0, 0);
        }
        Map<UUID, CoreUserCardResponse> cardMap = cards.stream()
                .filter(card -> card != null && card.userCardId() != null)
                .collect(java.util.stream.Collectors.toMap(CoreUserCardResponse::userCardId, card -> card, (a, b) -> a));
        int updated = 0;
        int imagesGenerated = 0;
        for (MissingFieldUpdate update : updates) {
            CoreUserCardResponse card = cardMap.get(update.userCardId());
            if (card == null || card.userCardId() == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = loadLatestContent(job.getJobId(), job.getDeckId(), card.userCardId(), accessToken, card.effectiveContent().deepCopy());
            boolean changed = false;
            Set<String> allowed = allowedFieldsByCard.get(card.userCardId());
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
                    if (!imageConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                        continue;
                    }
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    try {
                        MediaUpload upload = generateImage(job, apiKey, imageConfig, value.trim());
                        updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                        imagesGenerated++;
                        changed = true;
                    } catch (Exception ex) {
                        LOGGER.warn("Gemini image generation failed jobId={} cardId={} field={} model={} promptLength={}",
                                job.getJobId(),
                                update.userCardId(),
                                field,
                                imageConfig.model(),
                                value.length(),
                                ex);
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
        }
        return new MediaApplyResult(updated, imagesGenerated);
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

    private record MissingFieldUpdate(UUID userCardId, ObjectNode fields) {
    }

    private record CardDraft(ObjectNode content, Map<String, String> mediaPrompts) {
    }

    private record MediaApplyResult(int updatedCards, int imagesGenerated) {
    }

    private record ImageConfig(boolean enabled, String model, String format) {
    }

    private record MediaUpload(UUID mediaId, String contentType, String fileName) {
    }

    private record MissingCardSelection(List<CoreUserCardResponse> cards, Map<UUID, Set<String>> allowedFieldsByCard) {
    }

    private boolean isMissingAudio(JsonNode node) {
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

    private MissingCardSelection selectMissingCards(UUID deckId,
                                                    Set<String> targetFields,
                                                    JsonNode params,
                                                    String accessToken) {
        Map<String, Integer> fieldLimits = extractFieldLimits(params);
        if (!fieldLimits.isEmpty()) {
            java.util.LinkedHashMap<String, Integer> filtered = new java.util.LinkedHashMap<>();
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
                new CoreApiClient.MissingFieldCardsRequest(new java.util.ArrayList<>(targetFields), limit, null),
                accessToken
        );
        return new MissingCardSelection(cards, Map.of());
    }

    private MissingCardSelection selectMissingCardsWithLimits(UUID deckId,
                                                              Map<String, Integer> fieldLimits,
                                                              String accessToken) {
        if (fieldLimits.isEmpty()) {
            return new MissingCardSelection(List.of(), Map.of());
        }
        java.util.LinkedHashMap<UUID, CoreUserCardResponse> cards = new java.util.LinkedHashMap<>();
        Map<UUID, Set<String>> allowedFields = new java.util.HashMap<>();
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
                allowedFields.computeIfAbsent(card.userCardId(), __ -> new java.util.LinkedHashSet<>()).add(field);
            }
        }
        return new MissingCardSelection(new java.util.ArrayList<>(cards.values()), allowedFields);
    }

    private List<CoreUserCardResponse> filterCardsForPrompt(List<CoreUserCardResponse> cards,
                                                            List<String> fields,
                                                            Map<String, String> fieldTypes,
                                                            Map<UUID, Set<String>> allowedFieldsByCard) {
        if (fields.isEmpty()) {
            return List.of();
        }
        List<CoreUserCardResponse> result = new java.util.ArrayList<>();
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
        List<CoreUserCardResponse> result = new java.util.ArrayList<>();
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

    private record TtsApplyResult(int generated, int updatedCards, String model, String error) {
    }

    private String buildFieldHints(CoreTemplateResponse template, List<String> fields) {
        if (template == null || template.fields() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CoreFieldTemplate field : template.fields()) {
            if (field == null || field.name() == null || field.label() == null) {
                continue;
            }
            if (!fields.contains(field.name())) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(field.name()).append(": ").append(field.label());
        }
        return builder.toString();
    }

    private String formatAiProfile(JsonNode aiProfile) {
        if (aiProfile == null || aiProfile.isNull()) {
            return "";
        }
        if (aiProfile.isTextual()) {
            return aiProfile.asText();
        }
        if (aiProfile.isObject() && aiProfile.hasNonNull("prompt")) {
            return aiProfile.get("prompt").asText("");
        }
        try {
            return objectMapper.writeValueAsString(aiProfile);
        } catch (Exception ex) {
            return "";
        }
    }

    private JsonNode buildCardsSchema(List<String> fields, int count) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode cards = properties.putObject("cards");
        cards.put("type", "array");
        ObjectNode items = cards.putObject("items");
        items.put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        ObjectNode fieldsNode = itemProps.putObject("fields");
        fieldsNode.put("type", "object");
        ObjectNode fieldProps = fieldsNode.putObject("properties");
        for (String field : fields) {
            fieldProps.putObject(field).put("type", "string");
        }
        ArrayNode requiredFields = fieldsNode.putArray("required");
        for (String field : fields) {
            requiredFields.add(field);
        }
        items.putArray("required").add("fields");
        schema.putArray("required").add("cards");
        return schema;
    }

    private JsonNode buildMissingFieldsSchema(List<String> fields) {
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
        for (String field : fields) {
            fieldProps.putObject(field).put("type", "string");
        }
        items.putArray("required").add("userCardId").add("fields");
        schema.putArray("required").add("updates");
        return schema;
    }

    private JsonNode buildAuditSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
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
        return schema;
    }

    private JsonNode buildCardAuditSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
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
        itemSchema.putArray("required").add("field").add("message").add("suggestion");
        schema.putArray("required").add("summary").add("items");
        return schema;
    }

    private JsonNode buildCardMissingFieldsSchema(List<String> fields) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (String field : fields) {
            properties.putObject(field).put("type", "string");
        }
        ArrayNode required = schema.putArray("required");
        for (String field : fields) {
            required.add(field);
        }
        return schema;
    }

    private String buildCardAuditPrompt(CorePublicDeckResponse publicDeck,
                                        CoreTemplateResponse template,
                                        CoreApiClient.CoreUserCardDetail card) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are reviewing a single flashcard. Provide improvements only if they make the card clearer or more accurate. ");
        builder.append("Ignore any template quirks if the card content is solid. ");
        builder.append("Focus on clarity, correctness, examples, and language quality. ");
        builder.append("Return JSON with 'summary' and an 'items' array. Each item should contain 'field', 'message', 'suggestion', and optional 'severity'. ");

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
        if (hasFieldType(template, targetFields, "image")) {
            builder.append("For image fields, return a short visual prompt describing the image (not a URL). ");
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
                                                         String updateScope) {
        if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
            return new MediaApplyResult(0, 0);
        }
        if (response == null || !response.isObject()) {
            return new MediaApplyResult(0, 0);
        }
        ObjectNode updatedContent = card.effectiveContent().deepCopy();
        boolean changed = false;
        int imagesGenerated = 0;
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
                if (!imageConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                    continue;
                }
                try {
                    MediaUpload upload = generateImage(job, apiKey, imageConfig, text);
                    updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                    imagesGenerated++;
                    changed = true;
                } catch (Exception ex) {
                    LOGGER.warn("Gemini image generation failed jobId={} cardId={} field={} model={} promptLength={}",
                            job.getJobId(),
                            card.userCardId(),
                            field,
                            imageConfig.model(),
                            text.length(),
                            ex);
                }
            }
        }
        if (!changed) {
            return new MediaApplyResult(0, imagesGenerated);
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
        return new MediaApplyResult(1, imagesGenerated);
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
            ObjectNode content = objectMapper.createObjectNode();
            Map<String, String> mediaPrompts = new java.util.LinkedHashMap<>();
            for (String field : fields) {
                JsonNode value = fieldsNode.get(field);
                String fieldType = fieldTypes.get(field);
                if ("image".equals(fieldType)) {
                    String prompt = value != null && value.isTextual() ? value.asText().trim() : "";
                    if (!prompt.isBlank()) {
                        mediaPrompts.put(field, prompt);
                    }
                    continue;
                }
                content.set(field, value == null ? objectMapper.nullNode() : value);
            }
            ankiSupport.applyIfPresent(content, template);
            drafts.add(new CardDraft(content, mediaPrompts));
        }
        return drafts;
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

    private MediaApplyResult applyMediaPromptsToNewCards(AiJobEntity job,
                                                         String apiKey,
                                                         String accessToken,
                                                         CoreTemplateResponse template,
                                                         List<CoreUserCardResponse> createdCards,
                                                         List<CardDraft> drafts,
                                                         Map<String, String> fieldTypes,
                                                         ImageConfig imageConfig,
                                                         String updateScope) {
        if (createdCards == null || createdCards.isEmpty() || drafts == null || drafts.isEmpty()) {
            return new MediaApplyResult(0, 0);
        }
        int updated = 0;
        int imagesGenerated = 0;
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
            ObjectNode updatedContent = card.effectiveContent().deepCopy();
            boolean changed = false;
            for (Map.Entry<String, String> entry : mediaPrompts.entrySet()) {
                String field = entry.getKey();
                String prompt = entry.getValue();
                if (prompt == null || prompt.isBlank()) {
                    continue;
                }
                String fieldType = fieldTypes.get(field);
                if (!"image".equals(fieldType)) {
                    continue;
                }
                if (!imageConfig.enabled() || !isMissingMedia(updatedContent.get(field))) {
                    continue;
                }
                try {
                    MediaUpload upload = generateImage(job, apiKey, imageConfig, prompt.trim());
                    updatedContent.set(field, buildMediaNode(upload.mediaId(), "image"));
                    imagesGenerated++;
                    changed = true;
                } catch (Exception ex) {
                    LOGGER.warn("Gemini image generation failed jobId={} cardId={} field={} model={} promptLength={}",
                            job.getJobId(),
                            card.userCardId(),
                            field,
                            imageConfig.model(),
                            prompt.length(),
                            ex);
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
        }
        return new MediaApplyResult(updated, imagesGenerated);
    }

    private ImageConfig resolveImageConfig(JsonNode params, boolean hasPromptFields) {
        JsonNode node = params.path("image");
        boolean enabled = hasPromptFields;
        if (node.path("enabled").isBoolean()) {
            enabled = node.path("enabled").asBoolean();
        }
        String model = textOrDefault(node.path("model"), props.defaultImageModel());
        if (model == null || model.isBlank()) {
            model = "gemini-2.5-flash-image";
        }
        String format = textOrDefault(node.path("format"), "png");
        if (format == null || format.isBlank()) {
            format = "png";
        }
        return new ImageConfig(enabled, model, format);
    }

    private MediaUpload generateImage(AiJobEntity job, String apiKey, ImageConfig config, String prompt) {
        GeminiImageResult result = geminiClient.createImage(
                apiKey,
                new GeminiImageRequest(config.model(), prompt)
        );
        String contentType = result.mimeType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "image/" + config.format();
        }
        String extension = resolveImageExtension(contentType, config.format());
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
            LOGGER.warn("Gemini failed to load latest card content jobId={} cardId={}", jobId, cardId, ex);
        }
        return fallback;
    }

    private String resolveImageExtension(String mimeType, String format) {
        if (format != null && !format.isBlank()) {
            String trimmed = format.trim().toLowerCase();
            if (trimmed.contains("/")) {
                return trimmed.substring(trimmed.indexOf('/') + 1);
            }
            if (trimmed.startsWith(".")) {
                return trimmed.substring(1);
            }
            return trimmed;
        }
        if (mimeType == null) {
            return "png";
        }
        String normalized = mimeType.toLowerCase();
        if (normalized.contains("jpeg") || normalized.contains("jpg")) {
            return "jpg";
        }
        if (normalized.contains("webp")) {
            return "webp";
        }
        if (normalized.contains("gif")) {
            return "gif";
        }
        if (normalized.contains("png")) {
            return "png";
        }
        return "png";
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

        String model = resolveTtsModel(ttsNode.path("model"));
        String voice = resolveTtsVoice(ttsNode.path("voice"));
        String mimeType = resolveMimeType(ttsNode.path("mimeType"), ttsNode.path("format"));
        int maxChars = ttsNode.path("maxChars").isInt() ? ttsNode.path("maxChars").asInt() : 300;
        if (maxChars < 1) {
            maxChars = 1;
        }

        int generated = 0;
        int updatedCards = 0;
        String ttsError = null;
        for (CoreUserCardResponse card : createdCards) {
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = loadLatestContent(job.getJobId(), job.getDeckId(), card.userCardId(), accessToken, card.effectiveContent().deepCopy());
            boolean updated = false;
            for (TtsMapping mapping : mappings) {
                String text = extractTextValue(updatedContent, mapping.sourceField());
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (text.length() > maxChars) {
                    continue;
                }
                GeminiResponseParser.AudioResult audio;
                try {
                    audio = createSpeechWithRetry(
                            job,
                            apiKey,
                            new GeminiSpeechRequest(model, text, voice, mimeType),
                            card.userCardId(),
                            mapping.targetField()
                    );
                } catch (Exception ex) {
                    if (ttsError == null) {
                        ttsError = summarizeError(ex);
                        LOGGER.warn("Gemini TTS failed jobId={} cardId={} field={} error={}",
                                job.getJobId(),
                                card.userCardId(),
                                mapping.targetField(),
                                ttsError);
                    }
                    continue;
                }
                NormalizedAudio normalized = normalizeGeminiAudio(audio, mimeType);
                String contentType = normalized.mimeType();
                String extension = resolveAudioExtension(contentType);
                String fileName = "ai-tts-" + job.getJobId() + "-" + card.userCardId() + "." + extension;
                UUID mediaId = mediaApiClient.directUpload(
                        job.getUserId(),
                        "card_audio",
                        contentType,
                        fileName,
                        normalized.data().length,
                        new ByteArrayInputStream(normalized.data())
                );
                ObjectNode audioNode = objectMapper.createObjectNode();
                audioNode.put("mediaId", mediaId.toString());
                audioNode.put("kind", "audio");
                updatedContent.set(mapping.targetField(), audioNode);
                updated = true;
                generated++;
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
            }
        }
        return new TtsApplyResult(generated, updatedCards, model, ttsError);
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

        String model = resolveTtsModel(ttsNode.path("model"));
        String voice = resolveTtsVoice(ttsNode.path("voice"));
        String mimeType = resolveMimeType(ttsNode.path("mimeType"), ttsNode.path("format"));
        int maxChars = ttsNode.path("maxChars").isInt() ? ttsNode.path("maxChars").asInt() : 300;
        if (maxChars < 1) {
            maxChars = 1;
        }

        int generated = 0;
        int updatedCards = 0;
        String ttsError = null;
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
                String text = extractTextValue(card.effectiveContent(), mapping.sourceField());
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (text.length() > maxChars) {
                    continue;
                }
                GeminiResponseParser.AudioResult audio;
                try {
                    audio = createSpeechWithRetry(
                            job,
                            apiKey,
                            new GeminiSpeechRequest(model, text, voice, mimeType),
                            card.userCardId(),
                            mapping.targetField()
                    );
                } catch (Exception ex) {
                    if (ttsError == null) {
                        ttsError = summarizeError(ex);
                        LOGGER.warn("Gemini TTS failed jobId={} cardId={} field={} error={}",
                                job.getJobId(),
                                card.userCardId(),
                                mapping.targetField(),
                                ttsError);
                    }
                    continue;
                }
                NormalizedAudio normalized = normalizeGeminiAudio(audio, mimeType);
                String contentType = normalized.mimeType();
                String extension = resolveAudioExtension(contentType);
                String fileName = "ai-tts-" + job.getJobId() + "-" + card.userCardId() + "." + extension;
                UUID mediaId = mediaApiClient.directUpload(
                        job.getUserId(),
                        "card_audio",
                        contentType,
                        fileName,
                        normalized.data().length,
                        new ByteArrayInputStream(normalized.data())
                );
                ObjectNode audioNode = objectMapper.createObjectNode();
                audioNode.put("mediaId", mediaId.toString());
                audioNode.put("kind", "audio");
                updatedContent.set(mapping.targetField(), audioNode);
                updated = true;
                generated++;
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
            }
        }
        return new TtsApplyResult(generated, updatedCards, model, ttsError);
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
        String defaultTarget = audioFields.getFirst();
        mappings.add(new TtsMapping(defaultSource, defaultTarget));
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
        if (node == null || !node.isTextual()) {
            return null;
        }
        return node.asText().trim();
    }

    private String resolveAudioExtension(String mimeType) {
        if (mimeType == null) {
            return "wav";
        }
        String normalized = mimeType.toLowerCase();
        if (normalized.contains("mp3")) {
            return "mp3";
        }
        if (normalized.contains("ogg")) {
            return "ogg";
        }
        if (normalized.contains("wav")) {
            return "wav";
        }
        if (normalized.contains("m4a")) {
            return "m4a";
        }
        return "bin";
    }

    private String resolveMimeType(JsonNode mimeNode, JsonNode formatNode) {
        String mime = textOrDefault(mimeNode, props.defaultTtsMimeType());
        if (mime != null && mime.contains("/")) {
            return mime;
        }
        String format = textOrDefault(formatNode, null);
        String normalized = format == null ? null : format.trim().toLowerCase();
        if (normalized == null || normalized.isBlank()) {
            return mime;
        }
        if (!"wav".equals(normalized)) {
            return props.defaultTtsMimeType();
        }
        return "audio/wav";
    }

    private String resolveTtsModel(JsonNode node) {
        String provided = textOrDefault(node, null);
        if (provided != null && !provided.isBlank()) {
            String normalized = provided.trim().toLowerCase();
            if (normalized.contains("tts") || normalized.contains("speech")) {
                return provided.trim();
            }
            return props.defaultTtsModel();
        }
        return textOrDefault(node, props.defaultTtsModel());
    }

    private String resolveTtsVoice(JsonNode node) {
        String voice = textOrDefault(node, props.defaultVoice());
        if (voice == null) {
            return null;
        }
        String trimmed = voice.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return GEMINI_VOICES.getOrDefault(normalized, trimmed);
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

    private GeminiResponseParser.AudioResult createSpeechWithRetry(AiJobEntity job,
                                                                   String apiKey,
                                                                   GeminiSpeechRequest request,
                                                                   UUID cardId,
                                                                   String targetField) {
        int maxRetries = resolveTtsMaxRetries();
        long delayMs = resolveTtsRetryInitialDelayMs();
        long maxDelayMs = resolveTtsRetryMaxDelayMs();
        int attempts = 0;
        while (true) {
            throttleTtsRequests();
            try {
                return geminiClient.createSpeech(apiKey, request);
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
                LOGGER.warn("Gemini TTS rate limited jobId={} cardId={} field={} status={} waitMs={}",
                        job.getJobId(),
                        cardId,
                        targetField,
                        ex.getRawStatusCode(),
                        waitMs);
                if (!sleepQuietly(waitMs)) {
                    throw new IllegalStateException("Gemini TTS retry interrupted");
                }
                attempts++;
            }
        }
    }

    private void throttleTtsRequests() {
        int rpm = resolveTtsRequestsPerMinute();
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
            throw new IllegalStateException("Gemini TTS throttling interrupted");
        }
    }

    private int resolveTtsRequestsPerMinute() {
        Integer rpm = props.ttsRequestsPerMinute();
        if (rpm == null) {
            return DEFAULT_TTS_REQUESTS_PER_MINUTE;
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

    private NormalizedAudio normalizeGeminiAudio(GeminiResponseParser.AudioResult audio,
                                                 String requestedMimeType) {
        if (audio == null || audio.data() == null || audio.data().length == 0) {
            throw new IllegalStateException("Gemini audio payload is empty");
        }
        String mimeType = audio.mimeType();
        if (mimeType == null || mimeType.isBlank()) {
            if (requestedMimeType != null && requestedMimeType.toLowerCase(Locale.ROOT).contains("pcm")) {
                mimeType = requestedMimeType;
            } else {
                mimeType = "audio/pcm;rate=" + DEFAULT_PCM_SAMPLE_RATE;
            }
        }
        byte[] data = audio.data();
        if (mimeType != null && mimeType.toLowerCase().contains("pcm")) {
            int sampleRate = extractSampleRate(mimeType);
            data = wrapPcmToWav(data, sampleRate, (short) 1, (short) 16);
            mimeType = "audio/wav";
        }
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "audio/wav";
        }
        return new NormalizedAudio(data, mimeType);
    }

    private int extractSampleRate(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return DEFAULT_PCM_SAMPLE_RATE;
        }
        Matcher matcher = PCM_RATE_PATTERN.matcher(mimeType);
        if (matcher.find()) {
            try {
                int rate = Integer.parseInt(matcher.group(1));
                if (rate > 0) {
                    return rate;
                }
            } catch (NumberFormatException ignored) {
                // Use fallback below.
            }
        }
        return DEFAULT_PCM_SAMPLE_RATE;
    }

    private byte[] wrapPcmToWav(byte[] pcm, int sampleRate, short channels, short bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        short blockAlign = (short) (channels * bitsPerSample / 8);
        int dataSize = pcm.length;
        int chunkSize = 36 + dataSize;

        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(chunkSize);
        buffer.put(new byte[] {'W', 'A', 'V', 'E'});
        buffer.put(new byte[] {'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort(channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort(blockAlign);
        buffer.putShort(bitsPerSample);
        buffer.put(new byte[] {'d', 'a', 't', 'a'});
        buffer.putInt(dataSize);
        buffer.put(pcm);
        return buffer.array();
    }

    private record TtsMapping(String sourceField, String targetField) {
    }

    private record NormalizedAudio(byte[] data, String mimeType) {
    }
}
