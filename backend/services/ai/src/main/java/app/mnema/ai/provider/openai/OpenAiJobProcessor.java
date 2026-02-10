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
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AiImportContentService;
import app.mnema.ai.service.AiJobProcessingResult;
import app.mnema.ai.service.AiProviderProcessor;
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
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Duration VIDEO_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration VIDEO_POLL_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern RETRY_IN_PATTERN = Pattern.compile("retry in\\s*([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_TTS_REQUESTS_PER_MINUTE = 60;
    private static final int DEFAULT_TTS_MAX_RETRIES = 5;
    private static final long DEFAULT_TTS_RETRY_INITIAL_DELAY_MS = 2000L;
    private static final long DEFAULT_TTS_RETRY_MAX_DELAY_MS = 30000L;

    private final OpenAiClient openAiClient;
    private final OpenAiProps props;
    private final SecretVault secretVault;
    private final AiProviderCredentialRepository credentialRepository;
    private final MediaApiClient mediaApiClient;
    private final AiImportContentService importContentService;
    private final CoreApiClient coreApiClient;
    private final ObjectMapper objectMapper;
    private final AnkiTemplateSupport ankiSupport;
    private final Object ttsThrottleLock = new Object();
    private long nextTtsRequestAtMs = 0L;

    public OpenAiJobProcessor(OpenAiClient openAiClient,
                              OpenAiProps props,
                              SecretVault secretVault,
                              AiProviderCredentialRepository credentialRepository,
                              MediaApiClient mediaApiClient,
                              AiImportContentService importContentService,
                              CoreApiClient coreApiClient,
                              ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.props = props;
        this.secretVault = secretVault;
        this.credentialRepository = credentialRepository;
        this.mediaApiClient = mediaApiClient;
        this.importContentService = importContentService;
        this.coreApiClient = coreApiClient;
        this.objectMapper = objectMapper;
        this.ankiSupport = new AnkiTemplateSupport(objectMapper);
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
        UUID sourceMediaId = parseUuid(params.path("sourceMediaId").asText(null));
        String encoding = textOrNull(params.path("encoding"));
        String accessToken = job.getUserAccessToken();
        AiImportContentService.ImportTextPayload payload = importContentService.loadText(sourceMediaId, encoding, accessToken);

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
        UUID sourceMediaId = parseUuid(params.path("sourceMediaId").asText(null));
        String encoding = textOrNull(params.path("encoding"));
        String accessToken = job.getUserAccessToken();
        AiImportContentService.ImportTextPayload payload = importContentService.loadText(sourceMediaId, encoding, accessToken);

        ObjectNode updatedParams = params.isObject()
                ? ((ObjectNode) params).deepCopy()
                : objectMapper.createObjectNode();
        updatedParams.put("mode", MODE_GENERATE_CARDS);
        updatedParams.put("countLimit", MAX_IMPORT_CARDS);
        updatedParams.put("input", buildImportGeneratePrompt(payload, params));
        int requested = params.path("count").isInt() ? params.path("count").asInt() : 10;
        int total = Math.min(Math.max(requested, 1), MAX_IMPORT_CARDS);
        int batchSize = resolveImportBatchSize(params);
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

        OpenAiResponseResult response = null;
        MediaApplyResult mediaResult = new MediaApplyResult(0, 0, 0);
        if (!promptCards.isEmpty()) {
            String userPrompt = extractTextParam(params, "input", "prompt", "notes");
            String prompt = buildMissingFieldsPrompt(userPrompt, template, publicDeck, promptFields, promptCards, job.getDeckId(), accessToken);
            ObjectNode responseFormat = buildMissingFieldsResponseFormat(promptFields);
            String model = textOrDefault(params.path("model"), props.defaultModel());
            Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                    ? params.path("maxOutputTokens").asInt()
                    : null;

            response = openAiClient.createResponse(
                    apiKey,
                    new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
            );

            JsonNode parsed = parseJsonResponse(response.outputText());
            List<MissingFieldUpdate> updates = parseMissingFieldUpdates(parsed, promptFields);
            ImageConfig imageConfig = resolveImageConfig(params, !promptFields.isEmpty());
            VideoConfig videoConfig = resolveVideoConfig(params, !promptFields.isEmpty());
            mediaResult = applyMissingFieldUpdates(job, apiKey, accessToken, template, promptCards, updates, fieldTypes, selection.allowedFieldsByCard(), imageConfig, videoConfig, updateScope);
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
        if (mediaResult.videosGenerated() > 0) {
            summary.put("videosGenerated", mediaResult.videosGenerated());
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

        TtsApplyResult ttsResult = applyTtsForMissingAudio(job, apiKey, params, missingCards, template, targetFields, updateScope);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_MISSING_AUDIO);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("updatedCards", ttsResult.updatedCards());
        summary.put("ttsGenerated", ttsResult.generated());
        summary.put("candidates", missingCards.size());
        if (ttsResult.error() != null) {
            summary.put("ttsError", ttsResult.error());
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
        String updateScope = resolveUpdateScope(job, deck, publicDeck, params);

        int sampleLimit = resolveAuditSampleLimit(params.path("sampleLimit"));
        List<CoreUserCardResponse> cards = coreApiClient.getUserCards(job.getDeckId(), 1, sampleLimit, accessToken).content();
        List<String> targetFields = resolveAllowedFields(params, template);
        if (targetFields.isEmpty()) {
            throw new IllegalStateException("No supported fields to audit");
        }

        AuditAnalyzer.AuditContext analysis = AuditAnalyzer.analyze(objectMapper, template, cards, targetFields);
        String prompt = buildAuditPrompt(params, template, publicDeck, targetFields, cards, analysis);
        ObjectNode responseFormat = buildAuditResponseFormat();
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        OpenAiResponseResult response = openAiClient.createResponse(
                apiKey,
                new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
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
        ObjectNode responseFormat = buildCardAuditResponseFormat();
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        OpenAiResponseResult response = openAiClient.createResponse(
                apiKey,
                new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
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

        OpenAiResponseResult response = null;
        MediaApplyResult mediaResult = new MediaApplyResult(0, 0, 0);
        if (!promptFields.isEmpty()) {
            String prompt = buildCardMissingFieldsPrompt(publicDeck, template, card, promptFields);
            ObjectNode responseFormat = buildCardMissingFieldsResponseFormat(promptFields);
            String model = textOrDefault(params.path("model"), props.defaultModel());
            Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                    ? params.path("maxOutputTokens").asInt()
                    : null;

            response = openAiClient.createResponse(
                    apiKey,
                    new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
            );

            JsonNode parsed = parseJsonResponse(response.outputText());
            ImageConfig imageConfig = resolveImageConfig(params, true);
            VideoConfig videoConfig = resolveVideoConfig(params, true);
            mediaResult = applyCardMissingFieldUpdate(job, apiKey, accessToken, template, card, parsed, promptFields, fieldTypes, imageConfig, videoConfig, updateScope);
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
        if (mediaResult.videosGenerated() > 0) {
            summary.put("videosGenerated", mediaResult.videosGenerated());
        }
        if (!targetAudioFields.isEmpty()) {
            summary.put("ttsGenerated", ttsResult.generated());
            summary.put("ttsUpdatedCards", ttsResult.updatedCards());
            if (ttsError != null) {
                summary.put("ttsError", ttsError);
            }
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        LinkedHashSet<String> allFields = new LinkedHashSet<>();
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

    private AiJobProcessingResult handleFreeformText(AiJobEntity job, String apiKey, JsonNode params) {
        String input = extractTextParam(params, "input", "prompt", "text");
        if (input == null || input.isBlank()) {
            input = params.isMissingNode() ? "" : params.toString();
        }
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        OpenAiResponseResult response = openAiClient.createResponse(
                apiKey,
                new OpenAiResponseRequest(model, input, maxOutputTokens, null)
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

        String userPrompt = extractTextParam(params, "input", "prompt", "text");
        String prompt = buildCardsPrompt(userPrompt, template, publicDeck, allowedFields, count, job.getDeckId(), job.getUserAccessToken());
        JsonNode responseFormat = buildCardsSchema(allowedFields, count);
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        OpenAiResponseResult response = openAiClient.createResponse(
                apiKey,
                new OpenAiResponseRequest(model, prompt, maxOutputTokens, responseFormat)
        );

        JsonNode parsed = parseJsonResponse(response.outputText());
        Map<String, String> fieldTypes = resolveFieldTypes(template);
        List<CardDraft> drafts = buildCardDrafts(parsed, allowedFields, template, fieldTypes);
        List<CreateCardRequestPayload> cardRequests = drafts.stream()
                .map(draft -> new CreateCardRequestPayload(draft.content(), null, null, null, null, null))
                .toList();
        List<CreateCardRequestPayload> limitedRequests = cardRequests.stream()
                .limit(count)
                .toList();

        List<CoreUserCardResponse> createdCards = coreApiClient.addCards(
                job.getDeckId(),
                limitedRequests,
                accessToken,
                job.getJobId()
        );
        TtsApplyResult ttsResult = applyTtsIfNeeded(job, apiKey, params, createdCards, template, updateScope);
        ImageConfig imageConfig = resolveImageConfig(params, true);
        VideoConfig videoConfig = resolveVideoConfig(params, true);
        MediaApplyResult mediaResult = applyMediaPromptsToNewCards(job, apiKey, accessToken, template, createdCards, drafts, fieldTypes, imageConfig, videoConfig, updateScope);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("templateId", publicDeck.templateId().toString());
        summary.put("requestedCards", count);
        summary.put("createdCards", limitedRequests.size());
        if (ttsResult.generated() > 0) {
            summary.put("ttsGenerated", ttsResult.generated());
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

    private AiJobProcessingResult handleTts(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String text = extractTextParam(params, "text", "input", "prompt");
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("TTS text is required");
        }
        String model = textOrDefault(params.path("model"), props.defaultTtsModel());
        String voice = textOrDefault(params.path("voice"), props.defaultVoice());
        String format = textOrDefault(params.path("format"), props.defaultTtsFormat());

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
                    .orElseThrow(() -> new IllegalStateException("Provider credential not found"));
        }
        Optional<AiProviderCredentialEntity> credential = credentialRepository
                .findFirstByUserIdAndProviderAndStatusOrderByCreatedAtAsc(
                        job.getUserId(),
                        PROVIDER,
                        AiProviderStatus.active
                );
        return credential.orElseThrow(() -> new IllegalStateException("No active OpenAI credential"));
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
            return new MediaApplyResult(0, imagesGenerated, videosGenerated);
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
        coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), updateRequest, accessToken, cardScope);
        return new MediaApplyResult(1, imagesGenerated, videosGenerated);
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
            coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), updateRequest, accessToken, cardScope);
            updated++;
        }
        return new MediaApplyResult(updated, imagesGenerated, videosGenerated);
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

    private boolean isMissingAudio(JsonNode node) {
        return isMissingMedia(node);
    }

    private record MissingFieldUpdate(UUID userCardId, ObjectNode fields) {
    }

    private record CardDraft(ObjectNode content, Map<String, String> mediaPrompts) {
    }

    private record TtsApplyResult(int generated, int updatedCards, String model, String error) {
    }

    private record MediaApplyResult(int updatedCards, int imagesGenerated, int videosGenerated) {
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
                                    UUID deckId,
                                    String accessToken) {
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

    private JsonNode buildCardsSchema(List<String> fields, int count) {
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
        items.putArray("required").add("fields");
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
            drafts.add(new CardDraft(content, mediaPrompts));
        }
        return drafts;
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
        if (aiProfile == null || aiProfile.isNull()) {
            return "";
        }
        String raw = aiProfile.isTextual() ? aiProfile.asText() : aiProfile.toString();
        String trimmed = raw.trim();
        int max = 800;
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
        String voice = textOrDefault(ttsNode.path("voice"), props.defaultVoice());
        String format = textOrDefault(ttsNode.path("format"), props.defaultTtsFormat());
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
                coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), update, accessToken, cardScope);
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

        String model = textOrDefault(ttsNode.path("model"), props.defaultTtsModel());
        String voice = textOrDefault(ttsNode.path("voice"), props.defaultVoice());
        String format = textOrDefault(ttsNode.path("format"), props.defaultTtsFormat());
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
                coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), update, accessToken, cardScope);
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
                                                    JsonNode params,
                                                    String accessToken) {
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
            coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), update, accessToken, cardScope);
            updated++;
        }
        return new MediaApplyResult(updated, imagesGenerated, videosGenerated);
    }

    private ImageConfig resolveImageConfig(JsonNode params, boolean hasPromptFields) {
        JsonNode node = params.path("image");
        boolean enabled = hasPromptFields;
        if (node.path("enabled").isBoolean()) {
            enabled = node.path("enabled").asBoolean();
        }
        String model = textOrDefault(node.path("model"), props.defaultImageModel());
        if (model == null || model.isBlank()) {
            model = "gpt-image-1-mini";
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
            model = "sora-2";
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
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        return null;
    }

    private byte[] createSpeechWithRetry(AiJobEntity job,
                                         String apiKey,
                                         OpenAiSpeechRequest request,
                                         UUID cardId,
                                         String targetField) {
        int maxRetries = resolveTtsMaxRetries();
        long delayMs = resolveTtsRetryInitialDelayMs();
        long maxDelayMs = resolveTtsRetryMaxDelayMs();
        int attempts = 0;
        while (true) {
            throttleTtsRequests();
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
            throw new IllegalStateException("OpenAI TTS throttling interrupted");
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
}
