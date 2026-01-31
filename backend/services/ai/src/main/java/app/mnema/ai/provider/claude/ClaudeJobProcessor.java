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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ClaudeJobProcessor implements AiProviderProcessor {

    private static final String PROVIDER = "anthropic";
    private static final String MODE_GENERATE_CARDS = "generate_cards";
    private static final String MODE_MISSING_FIELDS = "missing_fields";
    private static final String MODE_MISSING_AUDIO = "missing_audio";
    private static final String MODE_AUDIT = "audit";
    private static final String MODE_ENHANCE = "enhance_deck";
    private static final int MAX_CARDS = 50;

    private final ClaudeClient claudeClient;
    private final ClaudeProps props;
    private final SecretVault secretVault;
    private final AiProviderCredentialRepository credentialRepository;
    private final CoreApiClient coreApiClient;
    private final ObjectMapper objectMapper;
    private final AnkiTemplateSupport ankiSupport;

    public ClaudeJobProcessor(ClaudeClient claudeClient,
                              ClaudeProps props,
                              SecretVault secretVault,
                              AiProviderCredentialRepository credentialRepository,
                              CoreApiClient coreApiClient,
                              ObjectMapper objectMapper) {
        this.claudeClient = claudeClient;
        this.props = props;
        this.secretVault = secretVault;
        this.credentialRepository = credentialRepository;
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
            throw new IllegalStateException("Claude provider does not support TTS");
        }
        return handleText(job, apiKey);
    }

    private AiJobProcessingResult handleText(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String mode = params.path("mode").asText();
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

    private AiJobProcessingResult handleFreeformText(AiJobEntity job, String apiKey, JsonNode params) {
        String input = extractTextParam(params, "input", "prompt", "text");
        if (input == null || input.isBlank()) {
            input = params.isMissingNode() ? "" : params.toString();
        }
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));

        ClaudeResponseResult response = claudeClient.createMessage(
                apiKey,
                new ClaudeMessageRequest(model, input, maxOutputTokens)
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

        int count = resolveCount(params);
        var allowedFields = resolveAllowedFields(params, template);
        if (allowedFields.isEmpty()) {
            throw new IllegalStateException("No supported fields to generate");
        }

        String userPrompt = extractTextParam(params, "input", "prompt", "text");
        String prompt = buildCardsPrompt(userPrompt, template, publicDeck, allowedFields, count, job.getDeckId(), job.getUserAccessToken());
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));

        ClaudeResponseResult response = claudeClient.createMessage(
                apiKey,
                new ClaudeMessageRequest(model, prompt, maxOutputTokens)
        );

        JsonNode parsed = parseJsonResponse(response.outputText());
        List<CreateCardRequestPayload> cardRequests = buildCardRequests(parsed, allowedFields, template);
        List<CreateCardRequestPayload> limitedRequests = cardRequests.stream()
                .limit(count)
                .toList();

        coreApiClient.addCards(job.getDeckId(), limitedRequests, accessToken);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("templateId", publicDeck.templateId().toString());
        summary.put("requestedCards", count);
        summary.put("createdCards", limitedRequests.size());
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

        List<String> targetFields = resolveAllowedFields(params, template);
        if (targetFields.isEmpty()) {
            throw new IllegalStateException("No supported fields to generate");
        }
        int limit = resolveLimit(params.path("limit"));
        List<CoreUserCardResponse> missingCards = coreApiClient.getMissingFieldCards(
                job.getDeckId(),
                new CoreApiClient.MissingFieldCardsRequest(targetFields, limit),
                accessToken
        );
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
        String prompt = buildMissingFieldsPrompt(userPrompt, template, publicDeck, targetFields, missingCards, job.getDeckId(), accessToken);
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));

        ClaudeResponseResult response = claudeClient.createMessage(
                apiKey,
                new ClaudeMessageRequest(model, prompt, maxOutputTokens)
        );

        JsonNode parsed = parseJsonResponse(response.outputText());
        List<MissingFieldUpdate> updates = parseMissingFieldUpdates(parsed, targetFields);
        int updated = applyMissingFieldUpdates(job, accessToken, template, missingCards, updates);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_MISSING_FIELDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("updatedCards", updated);
        summary.put("candidates", missingCards.size());
        ArrayNode fieldsNode = summary.putArray("fields");
        targetFields.forEach(fieldsNode::add);

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
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = resolveMaxTokens(params.path("maxOutputTokens"));

        ClaudeResponseResult response = claudeClient.createMessage(
                apiKey,
                new ClaudeMessageRequest(model, prompt, maxOutputTokens)
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

    private int applyMissingFieldUpdates(AiJobEntity job,
                                         String accessToken,
                                         CoreTemplateResponse template,
                                         List<CoreUserCardResponse> cards,
                                         List<MissingFieldUpdate> updates) {
        if (updates.isEmpty()) {
            return 0;
        }
        Map<UUID, CoreUserCardResponse> cardMap = cards.stream()
                .filter(card -> card != null && card.userCardId() != null)
                .collect(java.util.stream.Collectors.toMap(CoreUserCardResponse::userCardId, card -> card, (a, b) -> a));
        int updated = 0;
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
            coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), updateRequest, accessToken);
            updated++;
        }
        return updated;
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
