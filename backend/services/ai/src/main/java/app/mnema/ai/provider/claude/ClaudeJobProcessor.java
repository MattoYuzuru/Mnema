package app.mnema.ai.provider.claude;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.client.core.CoreApiClient.CoreFieldTemplate;
import app.mnema.ai.client.core.CoreApiClient.CorePublicDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreTemplateResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardPage;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CreateCardRequestPayload;
import app.mnema.ai.client.core.CoreApiClient.UpdateUserCardRequest;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AiJobExecutionService;
import app.mnema.ai.service.AiJobProcessingResult;
import app.mnema.ai.service.AiProviderProcessor;
import app.mnema.ai.service.CardNoveltyService;
import app.mnema.ai.provider.anki.AnkiTemplateSupport;
import app.mnema.ai.provider.audit.AuditAnalyzer;
import app.mnema.ai.vault.EncryptedSecret;
import app.mnema.ai.vault.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ClaudeJobProcessor implements AiProviderProcessor {

    private static final String PROVIDER = "anthropic";
    private static final String MODE_GENERATE_CARDS = "generate_cards";
    private static final String MODE_MISSING_FIELDS = "missing_fields";
    private static final String MODE_MISSING_AUDIO = "missing_audio";
    private static final String MODE_AUDIT = "audit";
    private static final String MODE_ENHANCE = "enhance_deck";
    private static final String MODE_IMPORT_PREVIEW = "import_preview";
    private static final String MODE_IMPORT_GENERATE = "import_generate";
    private static final int MAX_CARDS = 50;
    private static final int GENERATE_MAX_ATTEMPTS = 4;
    private static final int NOVELTY_HINT_LIMIT = 24;
    private static final String STEP_PREPARE_CONTEXT = "prepare_context";
    private static final String STEP_GENERATE_CONTENT = "generate_content";
    private static final String STEP_APPLY_CHANGES = "apply_changes";
    private static final String STEP_ANALYZE_CONTENT = "analyze_content";

    private final ClaudeClient claudeClient;
    private final ClaudeProps props;
    private final SecretVault secretVault;
    private final AiProviderCredentialRepository credentialRepository;
    private final CoreApiClient coreApiClient;
    private final CardNoveltyService noveltyService;
    private final ObjectMapper objectMapper;
    private final AnkiTemplateSupport ankiSupport;
    private final AiJobExecutionService executionService;

    public ClaudeJobProcessor(ClaudeClient claudeClient,
                              ClaudeProps props,
                              SecretVault secretVault,
                              AiProviderCredentialRepository credentialRepository,
                              CoreApiClient coreApiClient,
                              CardNoveltyService noveltyService,
                              ObjectMapper objectMapper,
                              AiJobExecutionService executionService) {
        this.claudeClient = claudeClient;
        this.props = props;
        this.secretVault = secretVault;
        this.credentialRepository = credentialRepository;
        this.coreApiClient = coreApiClient;
        this.noveltyService = noveltyService;
        this.objectMapper = objectMapper;
        this.ankiSupport = new AnkiTemplateSupport(objectMapper);
        this.executionService = executionService;
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

        executionService.resetPlan(job.getJobId(), resolveExecutionPlan(job));

        if (job.getType() == AiJobType.tts) {
            throw new IllegalStateException("Claude provider does not support TTS");
        }
        return handleText(job, apiKey);
    }

    private AiJobProcessingResult handleText(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String mode = params.path("mode").asText();
        if (MODE_IMPORT_PREVIEW.equalsIgnoreCase(mode) || MODE_IMPORT_GENERATE.equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Claude provider does not support import yet");
        }
        if (MODE_AUDIT.equalsIgnoreCase(mode)) {
            return handleAudit(job, apiKey, params);
        }
        if (MODE_ENHANCE.equalsIgnoreCase(mode) && hasAction(params, "audit")) {
            return handleAudit(job, apiKey, params);
        }
        if (MODE_MISSING_AUDIO.equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Claude provider does not support TTS");
        }
        if (MODE_MISSING_FIELDS.equalsIgnoreCase(mode)) {
            return handleMissingFields(job, apiKey, params);
        }
        if (MODE_GENERATE_CARDS.equalsIgnoreCase(mode)) {
            return handleGenerateCards(job, apiKey, params);
        }
        return handleFreeformText(job, apiKey, params);
    }

    private List<String> resolveExecutionPlan(AiJobEntity job) {
        if (job == null || job.getType() == AiJobType.tts) {
            return List.of();
        }
        JsonNode params = safeParams(job);
        String mode = params.path("mode").asText();
        if (MODE_AUDIT.equalsIgnoreCase(mode)) {
            return List.of(STEP_PREPARE_CONTEXT, STEP_ANALYZE_CONTENT);
        }
        if (MODE_MISSING_FIELDS.equalsIgnoreCase(mode)) {
            return List.of(STEP_PREPARE_CONTEXT, STEP_GENERATE_CONTENT, STEP_APPLY_CHANGES);
        }
        if (MODE_GENERATE_CARDS.equalsIgnoreCase(mode)) {
            return List.of(STEP_PREPARE_CONTEXT, STEP_GENERATE_CONTENT, STEP_APPLY_CHANGES);
        }
        return List.of(STEP_GENERATE_CONTENT);
    }

    private <T> T runStep(AiJobEntity job, String stepName, AiJobExecutionService.StepOperation<T> operation) {
        if (job == null || stepName == null || stepName.isBlank()) {
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

    private AiJobProcessingResult handleFreeformText(AiJobEntity job, String apiKey, JsonNode params) {
        String input = extractTextParam(params, "input", "prompt", "text");
        if (input == null || input.isBlank()) {
            input = params.isMissingNode() ? "" : params.toString();
        }
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));

        String finalInput = input;
        ClaudeResponseResult response = runStep(job, STEP_GENERATE_CONTENT, () -> claudeClient.createMessage(
                apiKey,
                new ClaudeMessageRequest(model, finalInput, maxOutputTokens)
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
        record GenerateContext(CorePublicDeckResponse publicDeck,
                               CoreTemplateResponse template,
                               int count,
                               List<String> allowedFields,
                               CardNoveltyService.NoveltyIndex noveltyIndex) {}
        GenerateContext context = runStep(job, STEP_PREPARE_CONTEXT, () -> {
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
            int count = resolveCount(params);
            List<String> allowedFields = resolveAllowedFields(params, template);
            if (allowedFields.isEmpty()) {
                throw new IllegalStateException("No supported fields to generate");
            }
            CardNoveltyService.NoveltyIndex noveltyIndex = noveltyService.buildIndex(job.getDeckId(), accessToken, allowedFields);
            return new GenerateContext(publicDeck, template, count, allowedFields, noveltyIndex);
        });

        String userPrompt = extractTextParam(params, "input", "prompt", "text");
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));

        record GeneratedCards(ClaudeResponseResult response,
                              List<CreateCardRequestPayload> requests,
                              int droppedEmpty,
                              int droppedExact,
                              int droppedPrimary,
                              int droppedSemantic) {}
        GeneratedCards generated = runStep(job, STEP_GENERATE_CONTENT, () -> {
            List<CreateCardRequestPayload> uniqueRequests = new java.util.ArrayList<>();
            int droppedEmpty = 0;
            int droppedExact = 0;
            int droppedPrimary = 0;
            int droppedSemantic = 0;
            ClaudeResponseResult response = null;

            for (int attempt = 0; attempt < GENERATE_MAX_ATTEMPTS && uniqueRequests.size() < context.count(); attempt++) {
                int remaining = context.count() - uniqueRequests.size();
                int candidateCount = resolveCandidateCount(remaining, attempt);
                String prompt = buildCardsPrompt(
                        augmentGeneratePrompt(userPrompt, context.noveltyIndex(), attempt),
                        context.template(),
                        context.publicDeck(),
                        context.allowedFields(),
                        candidateCount,
                        job.getDeckId(),
                        job.getUserAccessToken()
                );
                response = claudeClient.createMessage(
                        apiKey,
                        new ClaudeMessageRequest(model, prompt, maxOutputTokens)
                );

                JsonNode parsed = parseJsonResponse(response.outputText());
                List<CreateCardRequestPayload> cardRequests = buildCardRequests(parsed, context.allowedFields(), context.template());
                CardNoveltyService.FilterResult<CreateCardRequestPayload> filtered = noveltyService.filterCandidates(
                        cardRequests,
                        CreateCardRequestPayload::content,
                        context.allowedFields(),
                        context.noveltyIndex(),
                        remaining
                );
                uniqueRequests.addAll(filtered.accepted());
                droppedEmpty += filtered.droppedEmpty();
                droppedExact += filtered.droppedExact();
                droppedPrimary += filtered.droppedPrimary();
                droppedSemantic += filtered.droppedSemantic();
            }

            if (uniqueRequests.size() < context.count()) {
                throw new IllegalStateException("Failed to generate enough unique cards. Try a more specific prompt.");
            }

            return new GeneratedCards(
                    response,
                    uniqueRequests.stream().limit(context.count()).toList(),
                    droppedEmpty,
                    droppedExact,
                    droppedPrimary,
                    droppedSemantic
            );
        });

        List<CoreUserCardResponse> createdCards = runStep(job, STEP_APPLY_CHANGES, () -> coreApiClient.addCards(
                job.getDeckId(),
                generated.requests(),
                accessToken,
                job.getJobId()
        ));

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("templateId", context.publicDeck().templateId().toString());
        summary.put("requestedCards", context.count());
        summary.put("createdCards", generated.requests().size());
        summary.put("duplicatesSkippedExact", generated.droppedExact());
        summary.put("duplicatesSkippedPrimary", generated.droppedPrimary());
        summary.put("duplicatesSkippedSemantic", generated.droppedSemantic());
        if (generated.droppedEmpty() > 0) {
            summary.put("candidatesSkippedEmpty", generated.droppedEmpty());
        }
        ArrayNode fieldsNode = summary.putArray("fields");
        for (String field : context.allowedFields()) {
            fieldsNode.add(field);
        }
        summary.set("items", buildGeneratedItems(createdCards, context.allowedFields()));

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                generated.response().model(),
                generated.response().inputTokens(),
                generated.response().outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleMissingFields(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for missing field generation");
        }
        String accessToken = job.getUserAccessToken();
        record MissingFieldsContext(CoreTemplateResponse template,
                                    CorePublicDeckResponse publicDeck,
                                    String updateScope,
                                    List<String> targetFields,
                                    List<CoreUserCardResponse> missingCards) {}
        MissingFieldsContext context = runStep(job, STEP_PREPARE_CONTEXT, () -> {
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
            List<String> targetFields = resolveAllowedFields(params, template);
            if (targetFields.isEmpty()) {
                throw new IllegalStateException("No supported fields to generate");
            }
            List<CoreUserCardResponse> missingCards = selectMissingCards(job.getDeckId(), targetFields, params, accessToken);
            return new MissingFieldsContext(template, publicDeck, updateScope, targetFields, missingCards);
        });
        List<CoreUserCardResponse> missingCards = context.missingCards();
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

        String userPrompt = extractTextParam(params, "input", "prompt", "notes");
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));

        ClaudeResponseResult response = runStep(job, STEP_GENERATE_CONTENT, () -> {
            String prompt = buildMissingFieldsPrompt(userPrompt, context.template(), context.publicDeck(), context.targetFields(), missingCards, job.getDeckId(), accessToken);
            return claudeClient.createMessage(
                    apiKey,
                    new ClaudeMessageRequest(model, prompt, maxOutputTokens)
            );
        });

        JsonNode parsed = parseJsonResponse(response.outputText());
        List<MissingFieldUpdate> updates = parseMissingFieldUpdates(parsed, context.targetFields());
        MissingFieldApplyResult applyResult = runStep(job, STEP_APPLY_CHANGES, () -> applyMissingFieldUpdates(
                job,
                accessToken,
                context.template(),
                missingCards,
                updates,
                context.updateScope()
        ));

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_MISSING_FIELDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("updatedCards", applyResult.updatedCount());
        summary.put("candidates", missingCards.size());
        ArrayNode fieldsNode = summary.putArray("fields");
        context.targetFields().forEach(fieldsNode::add);
        summary.set("items", buildUpdatedItems(missingCards, applyResult.updatedCardIds()));

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

    private AiJobProcessingResult handleAudit(AiJobEntity job, String apiKey, JsonNode params) {
        if (job.getDeckId() == null) {
            throw new IllegalStateException("Deck id is required for audit");
        }
        String accessToken = job.getUserAccessToken();
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));
        record AuditContextData(CoreTemplateResponse template,
                                CorePublicDeckResponse publicDeck,
                                List<CoreUserCardResponse> cards,
                                List<String> targetFields,
                                AuditAnalyzer.AuditContext analysis) {}
        AuditContextData context = runStep(job, STEP_PREPARE_CONTEXT, () -> {
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

        ClaudeResponseResult response = runStep(job, STEP_ANALYZE_CONTENT, () -> {
            String prompt = buildAuditPrompt(params, context.template(), context.publicDeck(), context.targetFields(), context.cards(), context.analysis());
            return claudeClient.createMessage(
                    apiKey,
                    new ClaudeMessageRequest(model, prompt, maxOutputTokens)
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

    private UUID resolveUpdateOperationId(String updateScope, AiJobEntity job) {
        if (updateScope == null || !updateScope.equalsIgnoreCase("global")) {
            return null;
        }
        if (job == null) {
            return null;
        }
        return job.getJobId();
    }

    private Integer resolveMaxTokens(JsonNode node) {
        if (node != null && node.isInt()) {
            int value = node.asInt();
            if (value > 0) {
                return value;
            }
        }
        Integer fallback = props.defaultMaxTokens();
        return fallback != null && fallback > 0 ? fallback : 1024;
    }

    private int resolveCount(JsonNode params) {
        int requested = params.path("count").asInt(10);
        if (requested < 1) {
            requested = 1;
        }
        return Math.min(requested, MAX_CARDS);
    }

    private int resolveCandidateCount(int remaining, int attempt) {
        int safeRemaining = Math.max(1, remaining);
        int boosted = attempt == 0
                ? Math.max(safeRemaining * 3, safeRemaining + 10)
                : Math.max(safeRemaining * 2, safeRemaining + 6);
        return Math.min(boosted, 120);
    }

    private String augmentGeneratePrompt(String userPrompt, CardNoveltyService.NoveltyIndex noveltyIndex, int attempt) {
        if (attempt <= 0) {
            return userPrompt;
        }
        List<String> snippets = noveltyService.buildAvoidSnippets(noveltyIndex, NOVELTY_HINT_LIMIT);
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
        JsonNode fieldsNode = params.path("fields");
        if (fieldsNode.isArray()) {
            List<String> requested = new java.util.ArrayList<>();
            for (JsonNode field : fieldsNode) {
                String value = field.asText(null);
                if (value != null && !value.isBlank()) {
                    requested.add(value.trim());
                }
            }
            if (!requested.isEmpty()) {
                List<String> allowed = resolveTemplateFields(template);
                return requested.stream()
                        .filter(allowed::contains)
                        .toList();
            }
        }
        return resolveTemplateFields(template);
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
        return "text".equals(type) || "markdown".equals(type) || "rich_text".equals(type);
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

    private MissingFieldApplyResult applyMissingFieldUpdates(AiJobEntity job,
                                                             String accessToken,
                                                             CoreTemplateResponse template,
                                                             List<CoreUserCardResponse> cards,
                                                             List<MissingFieldUpdate> updates,
                                                             String updateScope) {
        if (updates.isEmpty()) {
            return new MissingFieldApplyResult(0, Set.of());
        }
        Map<UUID, CoreUserCardResponse> cardMap = cards.stream()
                .filter(card -> card != null && card.userCardId() != null)
                .collect(java.util.stream.Collectors.toMap(CoreUserCardResponse::userCardId, card -> card, (a, b) -> a));
        int updated = 0;
        Set<UUID> updatedCardIds = new java.util.LinkedHashSet<>();
        for (MissingFieldUpdate update : updates) {
            CoreUserCardResponse card = cardMap.get(update.userCardId());
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = card.effectiveContent().deepCopy();
            boolean changed = false;
            var it = update.fields().fields();
            while (it.hasNext()) {
                var entry = it.next();
                String field = entry.getKey();
                String value = entry.getValue().asText();
                if (isMissingText(updatedContent.get(field))) {
                    updatedContent.put(field, value);
                    changed = true;
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
        return new MissingFieldApplyResult(updated, updatedCardIds);
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

    private record MissingFieldApplyResult(int updatedCount, Set<UUID> updatedCardIds) {
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

    private List<CreateCardRequestPayload> buildCardRequests(JsonNode response,
                                                             List<String> fields,
                                                             CoreTemplateResponse template) {
        JsonNode cardsNode = response.path("cards");
        if (!cardsNode.isArray()) {
            throw new IllegalStateException("AI response missing cards array");
        }
        List<CreateCardRequestPayload> requests = new java.util.ArrayList<>();
        for (JsonNode cardNode : cardsNode) {
            JsonNode fieldsNode = cardNode.path("fields");
            if (!fieldsNode.isObject()) {
                continue;
            }
            ObjectNode content = objectMapper.createObjectNode();
            for (String field : fields) {
                JsonNode value = fieldsNode.get(field);
                content.set(field, value == null ? objectMapper.nullNode() : value);
            }
            ankiSupport.applyIfPresent(content, template);
            requests.add(new CreateCardRequestPayload(content, null, null, null, null, null));
        }
        return requests;
    }

    private ArrayNode buildGeneratedItems(List<CoreUserCardResponse> createdCards, List<String> preferredFields) {
        ArrayNode items = objectMapper.createArrayNode();
        if (createdCards == null || createdCards.isEmpty()) {
            return items;
        }
        for (CoreUserCardResponse card : createdCards) {
            if (card == null || card.userCardId() == null) {
                continue;
            }
            items.add(buildItemNode(card.userCardId(), extractPreview(card.effectiveContent(), preferredFields), List.of("text"), List.of()));
        }
        return items;
    }

    private ArrayNode buildUpdatedItems(List<CoreUserCardResponse> cards, Set<UUID> updatedCardIds) {
        ArrayNode items = objectMapper.createArrayNode();
        if (cards == null || cards.isEmpty()) {
            return items;
        }
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.userCardId() == null) {
                continue;
            }
            boolean updated = updatedCardIds != null && updatedCardIds.contains(card.userCardId());
            items.add(buildItemNode(
                    card.userCardId(),
                    extractPreview(card.effectiveContent(), List.of()),
                    updated ? List.of("content") : List.of(),
                    List.of()
            ));
        }
        return items;
    }

    private ObjectNode buildItemNode(UUID cardId,
                                     String preview,
                                     List<String> completedStages,
                                     List<String> errors) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("cardId", cardId.toString());
        if (preview != null && !preview.isBlank()) {
            item.put("preview", preview);
        }
        ArrayNode completedStagesNode = item.putArray("completedStages");
        if (completedStages != null) {
            completedStages.forEach(completedStagesNode::add);
        }
        if (errors != null && !errors.isEmpty()) {
            ArrayNode errorNode = item.putArray("errors");
            errors.forEach(errorNode::add);
        }
        String status;
        if (errors != null && !errors.isEmpty() && completedStagesNode.isEmpty()) {
            status = "failed";
        } else if (errors != null && !errors.isEmpty()) {
            status = "partial_success";
        } else if (!completedStagesNode.isEmpty()) {
            status = "completed";
        } else {
            status = "skipped";
        }
        item.put("status", status);
        return item;
    }

    private String extractPreview(JsonNode content, List<String> preferredFields) {
        if (content == null || !content.isObject()) {
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
        var fields = content.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String value = extractPreviewValue(entry.getValue());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private List<CoreUserCardResponse> selectMissingCards(UUID deckId,
                                                          List<String> targetFields,
                                                          JsonNode params,
                                                          String accessToken) {
        Set<UUID> requestedCardIds = resolveRequestedCardIds(params);
        if (!requestedCardIds.isEmpty()) {
            return selectRequestedCards(deckId, requestedCardIds, targetFields, accessToken);
        }
        int limit = resolveLimit(params.path("limit"));
        return coreApiClient.getMissingFieldCards(
                deckId,
                new CoreApiClient.MissingFieldCardsRequest(targetFields, limit, null),
                accessToken
        );
    }

    private List<CoreUserCardResponse> selectRequestedCards(UUID deckId,
                                                            Set<UUID> requestedCardIds,
                                                            List<String> targetFields,
                                                            String accessToken) {
        if (requestedCardIds == null || requestedCardIds.isEmpty()) {
            return List.of();
        }
        List<CoreUserCardResponse> cards = new java.util.ArrayList<>();
        for (UUID cardId : requestedCardIds) {
            if (cardId == null) {
                continue;
            }
            try {
                CoreApiClient.CoreUserCardDetail detail = coreApiClient.getUserCard(deckId, cardId, accessToken);
                if (detail == null || detail.effectiveContent() == null || !detail.effectiveContent().isObject()) {
                    continue;
                }
                boolean hasMissingTarget = false;
                for (String field : targetFields) {
                    if (field == null || field.isBlank()) {
                        continue;
                    }
                    if (isMissingText(detail.effectiveContent().get(field))) {
                        hasMissingTarget = true;
                        break;
                    }
                }
                if (!hasMissingTarget) {
                    continue;
                }
                cards.add(new CoreUserCardResponse(detail.userCardId(), detail.publicCardId(), detail.isCustom(), detail.effectiveContent()));
            } catch (Exception ignored) {
            }
        }
        return cards;
    }

    private Set<UUID> resolveRequestedCardIds(JsonNode params) {
        if (params == null || params.isNull()) {
            return Set.of();
        }
        JsonNode node = params.get("cardIds");
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        java.util.LinkedHashSet<UUID> cardIds = new java.util.LinkedHashSet<>();
        for (JsonNode item : node) {
            UUID parsed = parseUuid(item == null ? null : item.asText(null));
            if (parsed != null) {
                cardIds.add(parsed);
            }
        }
        return Set.copyOf(cardIds);
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

    private String extractPreviewValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
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
}
