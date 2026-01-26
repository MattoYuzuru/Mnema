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
import app.mnema.ai.service.AiJobProcessingResult;
import app.mnema.ai.service.AiProviderProcessor;
import app.mnema.ai.vault.EncryptedSecret;
import app.mnema.ai.vault.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class OpenAiJobProcessor implements AiProviderProcessor {

    private static final String PROVIDER = "openai";
    private static final String MODE_GENERATE_CARDS = "generate_cards";
    private static final int MAX_CARDS = 50;

    private final OpenAiClient openAiClient;
    private final OpenAiProps props;
    private final SecretVault secretVault;
    private final AiProviderCredentialRepository credentialRepository;
    private final MediaApiClient mediaApiClient;
    private final CoreApiClient coreApiClient;
    private final ObjectMapper objectMapper;

    public OpenAiJobProcessor(OpenAiClient openAiClient,
                              OpenAiProps props,
                              SecretVault secretVault,
                              AiProviderCredentialRepository credentialRepository,
                              MediaApiClient mediaApiClient,
                              CoreApiClient coreApiClient,
                              ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.props = props;
        this.secretVault = secretVault;
        this.credentialRepository = credentialRepository;
        this.mediaApiClient = mediaApiClient;
        this.coreApiClient = coreApiClient;
        this.objectMapper = objectMapper;
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
        CoreTemplateResponse template = coreApiClient.getTemplate(publicDeck.templateId(), accessToken);

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
        List<CreateCardRequestPayload> cardRequests = buildCardRequests(parsed, allowedFields);
        List<CreateCardRequestPayload> limitedRequests = cardRequests.stream()
                .limit(count)
                .toList();

        List<CoreUserCardResponse> createdCards = coreApiClient.addCards(job.getDeckId(), limitedRequests, accessToken);
        int ttsGenerated = applyTtsIfNeeded(job, apiKey, params, createdCards, template);

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", MODE_GENERATE_CARDS);
        summary.put("deckId", job.getDeckId().toString());
        summary.put("templateId", publicDeck.templateId().toString());
        summary.put("requestedCards", count);
        summary.put("createdCards", limitedRequests.size());
        if (ttsGenerated > 0) {
            summary.put("ttsGenerated", ttsGenerated);
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

        byte[] audio = openAiClient.createSpeech(
                apiKey,
                new OpenAiSpeechRequest(model, text, voice, format)
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

    private int resolveCount(JsonNode params) {
        int count = params.path("count").isInt() ? params.path("count").asInt() : 10;
        if (count < 1) {
            count = 1;
        }
        return Math.min(count, MAX_CARDS);
    }

    private List<String> resolveAllowedFields(JsonNode params, CoreTemplateResponse template) {
        List<String> templateFields = template.fields() == null
                ? List.of()
                : template.fields().stream()
                .filter(this::isTextField)
                .map(CoreFieldTemplate::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();

        List<String> requested = extractStringArray(params.path("fields"));
        if (requested.isEmpty()) {
            return templateFields;
        }
        return requested.stream()
                .filter(templateFields::contains)
                .distinct()
                .toList();
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
        String fieldList = String.join(", ", fields);
        StringBuilder builder = new StringBuilder();
        builder.append("You are generating flashcards for a deck template. ");
        builder.append("Return JSON that strictly matches the provided schema. ");
        builder.append("Use only these fields: ").append(fieldList).append(". ");
        builder.append("Create exactly ").append(count).append(" cards. ");
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

    private List<CreateCardRequestPayload> buildCardRequests(JsonNode response, List<String> fields) {
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
            boolean hasValue = false;
            for (String field : fields) {
                JsonNode valueNode = fieldsNode.get(field);
                if (valueNode == null || valueNode.isNull()) {
                    continue;
                }
                if (valueNode.isTextual()) {
                    String value = valueNode.asText().trim();
                    if (!value.isEmpty()) {
                        content.put(field, value);
                        hasValue = true;
                    }
                }
            }
            if (!hasValue) {
                continue;
            }
            requests.add(new CreateCardRequestPayload(content, null, null, null, null, null));
        }
        return requests;
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

    private int applyTtsIfNeeded(AiJobEntity job,
                                 String apiKey,
                                 JsonNode params,
                                 List<CoreUserCardResponse> createdCards,
                                 CoreTemplateResponse template) {
        JsonNode ttsNode = params.path("tts");
        if (!ttsNode.path("enabled").asBoolean(false)) {
            return 0;
        }
        if (createdCards == null || createdCards.isEmpty()) {
            return 0;
        }
        String accessToken = job.getUserAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            return 0;
        }
        List<String> audioFields = resolveAudioFields(template);
        if (audioFields.isEmpty()) {
            return 0;
        }
        List<String> textFields = resolveTextFields(template);
        if (textFields.isEmpty()) {
            return 0;
        }

        List<TtsMapping> mappings = resolveTtsMappings(ttsNode, textFields, audioFields, template);
        if (mappings.isEmpty()) {
            return 0;
        }

        String model = textOrDefault(ttsNode.path("model"), props.defaultTtsModel());
        String voice = textOrDefault(ttsNode.path("voice"), props.defaultVoice());
        String format = textOrDefault(ttsNode.path("format"), props.defaultTtsFormat());
        int maxChars = ttsNode.path("maxChars").isInt() ? ttsNode.path("maxChars").asInt() : 300;
        if (maxChars < 1) {
            maxChars = 1;
        }

        int generated = 0;
        for (CoreUserCardResponse card : createdCards) {
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode updatedContent = card.effectiveContent().deepCopy();
            boolean updated = false;
            for (TtsMapping mapping : mappings) {
                String text = extractTextValue(card.effectiveContent(), mapping.sourceField());
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (text.length() > maxChars) {
                    continue;
                }
                byte[] audio = openAiClient.createSpeech(
                        apiKey,
                        new OpenAiSpeechRequest(model, text, voice, format)
                );
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
                coreApiClient.updateUserCard(job.getDeckId(), card.userCardId(), update, accessToken);
            }
        }
        return generated;
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
        if (node != null && node.isTextual()) {
            return node.asText();
        }
        return null;
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
