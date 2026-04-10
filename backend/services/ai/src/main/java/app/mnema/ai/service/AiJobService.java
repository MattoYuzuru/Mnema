package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.AiJobCostResponse;
import app.mnema.ai.controller.dto.AiJobPreflightItemResponse;
import app.mnema.ai.controller.dto.AiJobPreflightResponse;
import app.mnema.ai.controller.dto.AiJobResultResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiJobRepository;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.repository.AiUsageLedgerRepository;
import app.mnema.ai.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

@Service
public class AiJobService {

    private final AiJobRepository jobRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AiQuotaService quotaService;
    private final AiProviderCredentialRepository credentialRepository;
    private final AiUsageLedgerRepository usageLedgerRepository;
    private final ObjectMapper objectMapper;
    private final AiJobExecutionService executionService;
    private final AiJobEtaEstimator etaEstimator;
    private final AiJobCostEstimator costEstimator;

    public AiJobService(AiJobRepository jobRepository,
                        CurrentUserProvider currentUserProvider,
                        AiQuotaService quotaService,
                        AiProviderCredentialRepository credentialRepository,
                        AiUsageLedgerRepository usageLedgerRepository,
                        ObjectMapper objectMapper,
                        AiJobExecutionService executionService,
                        AiJobEtaEstimator etaEstimator,
                        AiJobCostEstimator costEstimator) {
        this.jobRepository = jobRepository;
        this.currentUserProvider = currentUserProvider;
        this.quotaService = quotaService;
        this.credentialRepository = credentialRepository;
        this.usageLedgerRepository = usageLedgerRepository;
        this.objectMapper = objectMapper;
        this.executionService = executionService;
        this.etaEstimator = etaEstimator;
        this.costEstimator = costEstimator;
    }

    @Transactional
    public AiJobResponse createJob(Jwt jwt, String accessToken, CreateAiJobRequest request) {
        UUID userId = requireUserId(jwt);
        String token = requireAccessToken(accessToken);
        UUID requestId = requireRequestId(request.requestId());
        AiJobType jobType = defaultType(request.type());
        JsonNode params = request.params() == null ? NullNode.getInstance() : request.params();
        String expectedHash = resolveInputHash(request.inputHash(), jobType, request.deckId(), params);

        Optional<AiJobEntity> existing = jobRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            AiJobEntity job = existing.get();
            if (!job.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used");
            }
            if (!Objects.equals(job.getInputHash(), expectedHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used with different input");
            }
            return toResponse(job);
        }

        Instant now = Instant.now();
        ProviderInfo requestProviderInfo = resolveProviderInfo(userId, params);
        AiJobCostEstimator.PlannedCost plannedCost = costEstimator.estimatePlanned(jobType, params, requestProviderInfo.provider(), requestProviderInfo.model());
        int estimatedTokens = estimateTokens(jobType, request.deckId(), params, plannedCost);

        AiJobEntity job = new AiJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setRequestId(requestId);
        job.setUserId(userId);
        job.setDeckId(request.deckId());
        job.setUserAccessToken(token);
        job.setType(jobType);
        job.setStatus(AiJobStatus.queued);
        job.setProgress(0);
        job.setParamsJson(params);
        job.setInputHash(expectedHash);
        job.setResultSummary(NullNode.getInstance());
        job.setAttempts(0);
        job.setNextRunAt(null);
        job.setErrorMessage(null);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        try {
            AiJobEntity saved = jobRepository.save(job);
            quotaService.consumeTokens(userId, estimatedTokens);
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            AiJobEntity duplicate = jobRepository.findByRequestId(requestId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used"));
            if (!duplicate.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used");
            }
            if (!Objects.equals(duplicate.getInputHash(), expectedHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used with different input");
            }
            return toResponse(duplicate);
        }
    }

    @Transactional(readOnly = true)
    public AiJobPreflightResponse preflightJob(Jwt jwt, CreateAiJobRequest request) {
        UUID userId = requireUserId(jwt);
        AiJobType jobType = defaultType(request.type());
        JsonNode normalizedParams = normalizeParams(request.params());
        ProviderInfo providerInfo = resolveProviderInfo(userId, normalizedParams);
        String mode = resolveMode(normalizedParams);
        Integer targetCount = resolveTargetCount(jobType, normalizedParams);
        List<String> fields = resolveFields(normalizedParams);
        List<String> plannedStages = resolvePlannedStages(mode, normalizedParams);
        List<AiJobPreflightItemResponse> items = buildPlannedItems(mode, normalizedParams, fields, plannedStages, targetCount);
        List<String> warnings = buildPreflightWarnings(mode, items, targetCount, providerInfo.provider(), normalizedParams);
        AiJobEtaEstimator.EtaEstimate eta = etaEstimator.estimatePlanned(jobType, normalizedParams, providerInfo.provider());
        AiJobCostResponse cost = costEstimator.buildPlannedSnapshot(jobType, normalizedParams, providerInfo.provider(), providerInfo.model());
        return new AiJobPreflightResponse(
                request.deckId(),
                jobType,
                providerInfo.credentialId(),
                providerInfo.provider(),
                providerInfo.alias(),
                providerInfo.model(),
                mode,
                normalizedParams,
                buildPreflightSummary(mode, targetCount, fields, plannedStages),
                targetCount,
                fields,
                plannedStages,
                warnings,
                items,
                cost,
                eta.estimatedSecondsRemaining(),
                eta.estimatedCompletionAt(),
                eta.queueAhead()
        );
    }

    @Transactional(readOnly = true)
    public java.util.List<AiJobResponse> listJobs(Jwt jwt, UUID deckId, int limit) {
        UUID userId = requireUserId(jwt);
        if (deckId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deckId is required");
        }
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<AiJobEntity> jobs = jobRepository
                .findByUserIdAndDeckIdOrderByCreatedAtDesc(userId, deckId, PageRequest.of(0, safeLimit))
                .getContent();
        Map<UUID, AiUsageLedgerEntity> usageByJobId = loadUsageByJobId(jobs);
        return jobs.stream()
                .map(job -> toResponse(job, usageByJobId.get(job.getJobId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AiJobResponse getJob(Jwt jwt, UUID jobId) {
        UUID userId = requireUserId(jwt);
        AiJobEntity job = jobRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI job not found"));
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public AiJobResultResponse getJobResult(Jwt jwt, UUID jobId) {
        UUID userId = requireUserId(jwt);
        AiJobEntity job = jobRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI job not found"));
        AiJobExecutionService.ExecutionSnapshot snapshot = executionService.snapshot(job.getJobId());
        return new AiJobResultResponse(job.getJobId(), job.getStatus(), job.getResultSummary(), snapshot.steps());
    }

    @Transactional
    public AiJobResponse cancelJob(Jwt jwt, UUID jobId) {
        UUID userId = requireUserId(jwt);
        AiJobEntity job = jobRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI job not found"));
        if (job.getStatus() == AiJobStatus.completed || job.getStatus() == AiJobStatus.failed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI job already finished");
        }
        if (job.getStatus() == AiJobStatus.canceled) {
            return toResponse(job);
        }
        job.setStatus(AiJobStatus.canceled);
        job.setUpdatedAt(Instant.now());
        job.setCompletedAt(Instant.now());
        return toResponse(jobRepository.save(job));
    }

    @Transactional
    public AiJobResponse retryFailedJob(Jwt jwt, String accessToken, UUID jobId) {
        UUID userId = requireUserId(jwt);
        AiJobEntity job = jobRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI job not found"));
        if (!isRetryableTerminalStatus(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI job is not ready for retry");
        }
        CreateAiJobRequest retryRequest = buildRetryRequest(job);
        return createJob(jwt, accessToken, retryRequest);
    }

    private UUID requireUserId(Jwt jwt) {
        try {
            return currentUserProvider.requireUserId(jwt);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
    }

    private UUID requireRequestId(UUID requestId) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }
        return requestId;
    }

    private String requireAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing access token");
        }
        return accessToken;
    }

    private AiJobType defaultType(AiJobType type) {
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        return type;
    }

    private String resolveInputHash(String inputHash, AiJobType type, UUID deckId, JsonNode params) {
        if (inputHash != null && !inputHash.isBlank()) {
            return inputHash;
        }
        return computeHash(type, deckId, params);
    }

    private int estimateTokens(AiJobType type, UUID deckId, JsonNode params, AiJobCostEstimator.PlannedCost plannedCost) {
        int plannedTokens = 0;
        if (plannedCost != null) {
            plannedTokens = Math.max(0, nullToZero(plannedCost.inputTokens()) + nullToZero(plannedCost.outputTokens()));
        }
        if (plannedTokens > 0) {
            return plannedTokens;
        }
        try {
            Map<String, Object> payloadMap = new java.util.LinkedHashMap<>();
            payloadMap.put("type", type);
            payloadMap.put("deckId", deckId);
            payloadMap.put("params", params);
            byte[] payload = objectMapper.writeValueAsBytes(payloadMap);
            int estimated = (int) Math.ceil(payload.length / 4.0);
            return Math.max(1, estimated);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to estimate token usage", ex);
        }
    }

    private String computeHash(AiJobType type, UUID deckId, JsonNode params) {
        try {
            Map<String, Object> payloadMap = new java.util.LinkedHashMap<>();
            payloadMap.put("type", type);
            payloadMap.put("deckId", deckId);
            payloadMap.put("params", params);
            byte[] payload = objectMapper.writeValueAsBytes(payloadMap);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute input hash", ex);
        }
    }

    private CreateAiJobRequest buildRetryRequest(AiJobEntity job) {
        ObjectNode params = copyParams(job.getParamsJson());
        ObjectNode summary = asObject(job.getResultSummary());
        String mode = textOrNull(summary.path("mode"));
        if (mode == null) {
            mode = textOrNull(params.path("mode"));
        }
        if (mode == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI job mode is not available for retry");
        }

        List<RetryItem> retryItems = extractRetryItems(summary.path("items"));
        if (retryItems.isEmpty() && !isSingleCardRetryMode(mode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI job has no failed items to retry");
        }

        params.put("retryOfJobId", job.getJobId().toString());
        params.put("retryStrategy", "failed_items");

        switch (mode) {
            case "generate_cards" -> buildGenerateRetryParams(params, summary, retryItems);
            case "missing_fields", "missing_audio" -> addRetryCardIds(params, retryItems);
            case "card_missing_fields", "card_missing_audio" -> {
                if (retryItems.isEmpty() && parseUuid(params.path("cardId").asText(null)) == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "AI job has no failed card to retry");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Retry is not supported for this AI job");
        }

        return new CreateAiJobRequest(
                UUID.randomUUID(),
                job.getDeckId(),
                job.getType(),
                params,
                null,
                null,
                null
        );
    }

    private void buildGenerateRetryParams(ObjectNode params, ObjectNode summary, List<RetryItem> retryItems) {
        addRetryCardIds(params, retryItems);
        params.put("mode", "missing_fields");
        if (!params.hasNonNull("updateScope")) {
            params.put("updateScope", "local");
        }
        params.remove(List.of("count", "countLimit"));
        if (!params.has("fields") && summary.has("fields")) {
            params.set("fields", summary.get("fields").deepCopy());
        }
    }

    private void addRetryCardIds(ObjectNode params, List<RetryItem> retryItems) {
        LinkedHashSet<UUID> cardIds = retryItems.stream()
                .map(RetryItem::cardId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (cardIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI job has no retryable card ids");
        }
        ArrayNode cardIdsNode = params.putArray("cardIds");
        cardIds.forEach(cardId -> cardIdsNode.add(cardId.toString()));
    }

    private List<RetryItem> extractRetryItems(JsonNode itemsNode) {
        if (itemsNode == null || !itemsNode.isArray()) {
            return List.of();
        }
        List<RetryItem> items = new java.util.ArrayList<>();
        for (JsonNode item : itemsNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String status = textOrNull(item.get("status"));
            if (!isRetryableItemStatus(status)) {
                continue;
            }
            items.add(new RetryItem(parseUuid(textOrNull(item.get("cardId"))), status));
        }
        return items;
    }

    private boolean isRetryableItemStatus(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.trim().toLowerCase()) {
            case "failed", "partial_success", "skipped" -> true;
            default -> false;
        };
    }

    private boolean isRetryableTerminalStatus(AiJobStatus status) {
        return status == AiJobStatus.completed || status == AiJobStatus.partial_success || status == AiJobStatus.failed;
    }

    private boolean isSingleCardRetryMode(String mode) {
        return "card_missing_fields".equalsIgnoreCase(mode) || "card_missing_audio".equalsIgnoreCase(mode);
    }

    private ObjectNode copyParams(JsonNode params) {
        if (params == null || params.isNull() || !params.isObject()) {
            return objectMapper.createObjectNode();
        }
        return params.deepCopy();
    }

    private ObjectNode asObject(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return objectMapper.createObjectNode();
        }
        return (ObjectNode) node;
    }

    private JsonNode normalizeParams(JsonNode params) {
        JsonNode source = params == null || params.isNull() ? objectMapper.createObjectNode() : params;
        JsonNode normalized = normalizeNode(source);
        if (normalized == null || normalized.isNull() || !normalized.isObject()) {
            return objectMapper.createObjectNode();
        }
        return normalized;
    }

    private JsonNode normalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return NullNode.getInstance();
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            return value.isEmpty() ? NullNode.getInstance() : JsonNodeFactory.instance.textNode(value);
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                JsonNode normalized = normalizeNode(item);
                if (normalized != null && !normalized.isNull()) {
                    array.add(normalized);
                }
            }
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                JsonNode normalized = normalizeNode(entry.getValue());
                if (normalized != null && !normalized.isNull()) {
                    object.set(entry.getKey(), normalized);
                }
            });
            return object;
        }
        return node.deepCopy();
    }

    private String resolveMode(JsonNode params) {
        String mode = textOrNull(params.path("mode"));
        return mode == null ? "generic" : mode.toLowerCase(java.util.Locale.ROOT);
    }

    private Integer resolveTargetCount(AiJobType type, JsonNode params) {
        JsonNode cardIds = params.path("cardIds");
        if (cardIds.isArray() && !cardIds.isEmpty()) {
            return clamp(cardIds.size(), 1, 100);
        }
        if ("card_missing_fields".equals(resolveMode(params)) || "card_missing_audio".equals(resolveMode(params))) {
            return 1;
        }
        int count = positiveInt(params.path("count"), 0);
        if (count > 0) {
            return clamp(count, 1, 100);
        }
        int limit = positiveInt(params.path("limit"), 0);
        if (limit > 0) {
            return clamp(limit, 1, 100);
        }
        int fieldLimit = maxFieldLimit(params.path("fieldLimits"));
        if (fieldLimit > 0) {
            return clamp(fieldLimit, 1, 100);
        }
        if (type == AiJobType.tts) {
            return 1;
        }
        return switch (resolveMode(params)) {
            case "generate_cards", "import_generate" -> 10;
            case "missing_fields", "missing_audio", "audit" -> 3;
            default -> 1;
        };
    }

    private List<String> resolveFields(JsonNode params) {
        JsonNode fieldsNode = params.path("fields");
        if (fieldsNode.isArray() && !fieldsNode.isEmpty()) {
            return streamTextValues(fieldsNode);
        }
        JsonNode fieldLimits = params.path("fieldLimits");
        if (fieldLimits.isArray() && !fieldLimits.isEmpty()) {
            LinkedHashSet<String> fields = new LinkedHashSet<>();
            for (JsonNode fieldLimit : fieldLimits) {
                String field = textOrNull(fieldLimit.path("field"));
                if (field != null) {
                    fields.add(field);
                }
            }
            return List.copyOf(fields);
        }
        return List.of();
    }

    private List<String> resolvePlannedStages(String mode, JsonNode params) {
        LinkedHashSet<String> stages = new LinkedHashSet<>();
        if (mode.contains("audit")) {
            stages.add("audit");
        } else {
            stages.add("text");
        }
        if (isGenerationEnabled(params.path("tts")) || mode.contains("audio")) {
            stages.add("audio");
        }
        if (isGenerationEnabled(params.path("image"))) {
            stages.add("image");
        }
        if (isGenerationEnabled(params.path("video"))) {
            stages.add("video");
        }
        return List.copyOf(stages);
    }

    private List<AiJobPreflightItemResponse> buildPlannedItems(String mode,
                                                               JsonNode params,
                                                               List<String> fields,
                                                               List<String> plannedStages,
                                                               Integer targetCount) {
        ArrayList<AiJobPreflightItemResponse> items = new ArrayList<>();
        int safeTargetCount = targetCount == null ? 1 : Math.max(1, targetCount);
        if ("generate_cards".equals(mode) || "import_generate".equals(mode)) {
            int limit = Math.min(safeTargetCount, 10);
            String itemType = "generate_cards".equals(mode) ? "new_card" : "import_card";
            for (int index = 0; index < limit; index++) {
                items.add(new AiJobPreflightItemResponse(itemType, null, "New card " + (index + 1), fields, plannedStages));
            }
            return items;
        }
        JsonNode cardIds = params.path("cardIds");
        if (cardIds.isArray() && !cardIds.isEmpty()) {
            int limit = Math.min(cardIds.size(), 12);
            for (int index = 0; index < limit; index++) {
                UUID cardId = parseUuid(cardIds.get(index).asText(null));
                items.add(new AiJobPreflightItemResponse("existing_card", cardId, formatCardPreview(cardId), fields, plannedStages));
            }
            return items;
        }
        UUID cardId = parseUuid(params.path("cardId").asText(null));
        if (cardId != null) {
            items.add(new AiJobPreflightItemResponse("existing_card", cardId, formatCardPreview(cardId), fields, plannedStages));
            return items;
        }
        JsonNode fieldLimits = params.path("fieldLimits");
        if (fieldLimits.isArray() && !fieldLimits.isEmpty()) {
            for (JsonNode fieldLimit : fieldLimits) {
                String field = textOrNull(fieldLimit.path("field"));
                int limit = positiveInt(fieldLimit.path("limit"), safeTargetCount);
                if (field != null) {
                    items.add(new AiJobPreflightItemResponse(
                            "field_scope",
                            null,
                            field + " · up to " + limit + " cards",
                            List.of(field),
                            plannedStages
                    ));
                }
                if (items.size() >= 10) {
                    break;
                }
            }
            return items;
        }
        items.add(new AiJobPreflightItemResponse("deck_scope", null, "Deck-wide analysis", fields, plannedStages));
        return items;
    }

    private List<String> buildPreflightWarnings(String mode,
                                                List<AiJobPreflightItemResponse> items,
                                                Integer targetCount,
                                                String provider,
                                                JsonNode params) {
        ArrayList<String> warnings = new ArrayList<>();
        if (targetCount != null && targetCount > items.size()) {
            warnings.add("Showing the first " + items.size() + " planned items.");
        }
        if (mode.contains("audio") || items.stream().anyMatch(item -> item.plannedStages().contains("audio"))) {
            warnings.add("Audio generation can dominate runtime on larger batches.");
        }
        String normalizedProvider = normalizeProvider(provider);
        if ("ollama".equals(normalizedProvider)) {
            if (isGenerationEnabled(params.path("tts")) || mode.contains("audio")) {
                warnings.add("Local TTS depends on your self-host audio backend or Ollama experimental audio endpoints. Verify the gateway and selected voice/model before long runs.");
            }
            if (isGenerationEnabled(params.path("image"))) {
                warnings.add("Local image generation can be experimental depending on the configured backend and GPU memory. Expect longer runtime on larger batches.");
            }
            if (isGenerationEnabled(params.path("video"))) {
                warnings.add("Local video generation is the heaviest self-host path. Use short duration, low concurrency, and confirm the video backend is configured.");
            }
        }
        return warnings;
    }

    private String buildPreflightSummary(String mode,
                                         Integer targetCount,
                                         List<String> fields,
                                         List<String> plannedStages) {
        String fieldSummary = fields.isEmpty() ? "default fields" : String.join(", ", fields);
        int safeTargetCount = targetCount == null ? 1 : Math.max(1, targetCount);
        return switch (mode) {
            case "generate_cards" -> "Create " + safeTargetCount + " new cards with " + fieldSummary + ".";
            case "import_generate" -> "Create about " + safeTargetCount + " cards from the imported source.";
            case "missing_fields", "card_missing_fields" -> "Fill missing fields for up to " + safeTargetCount + " card(s): " + fieldSummary + ".";
            case "missing_audio", "card_missing_audio" -> "Generate missing audio for up to " + safeTargetCount + " card(s).";
            case "audit", "card_audit" -> "Run an audit for up to " + safeTargetCount + " card(s).";
            default -> "Run " + String.join(", ", plannedStages) + " for up to " + safeTargetCount + " planned item(s).";
        };
    }

    private String formatCardPreview(UUID cardId) {
        if (cardId == null) {
            return "Card";
        }
        String raw = cardId.toString();
        return "Card " + raw.substring(0, Math.min(raw.length(), 8));
    }

    private boolean isGenerationEnabled(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.has("enabled")) {
            return node.path("enabled").asBoolean(false);
        }
        return node.isObject() && node.size() > 0;
    }

    private int maxFieldLimit(JsonNode fieldLimitsNode) {
        if (fieldLimitsNode == null || !fieldLimitsNode.isArray()) {
            return 0;
        }
        int max = 0;
        for (JsonNode fieldLimit : fieldLimitsNode) {
            max = Math.max(max, positiveInt(fieldLimit.path("limit"), 0));
        }
        return max;
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<String> streamTextValues(JsonNode arrayNode) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode node : arrayNode) {
            String value = textOrNull(node);
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private AiJobResponse toResponse(AiJobEntity job) {
        AiUsageLedgerEntity usage = loadUsageByJobId(List.of(job)).get(job.getJobId());
        return toResponse(job, usage);
    }

    private AiJobResponse toResponse(AiJobEntity job, AiUsageLedgerEntity usage) {
        ProviderInfo providerInfo = resolveProviderInfo(job);
        AiJobExecutionService.ExecutionSnapshot snapshot = executionService.snapshot(job.getJobId());
        AiJobEtaEstimator.EtaEstimate eta = etaEstimator.estimate(job, snapshot, providerInfo.provider());
        AiJobCostResponse cost = costEstimator.buildSnapshot(job, providerInfo.provider(), providerInfo.model(), usage);
        return new AiJobResponse(
                job.getJobId(),
                job.getRequestId(),
                job.getDeckId(),
                job.getType(),
                job.getStatus(),
                job.getProgress(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage(),
                providerInfo.credentialId(),
                providerInfo.provider(),
                providerInfo.alias(),
                providerInfo.model(),
                snapshot.currentStep(),
                snapshot.completedSteps(),
                snapshot.totalSteps(),
                cost,
                eta.estimatedSecondsRemaining(),
                eta.estimatedCompletionAt(),
                eta.queueAhead()
        );
    }

    private ProviderInfo resolveProviderInfo(AiJobEntity job) {
        return resolveProviderInfo(job.getUserId(), job.getParamsJson());
    }

    private ProviderInfo resolveProviderInfo(UUID userId, JsonNode params) {
        if (params == null || params.isNull()) {
            return new ProviderInfo(null, null, null, null);
        }
        UUID credentialId = parseUuid(params.path("providerCredentialId").asText(null));
        String providerFromParams = textOrNull(params.path("provider"));
        String model = textOrNull(params.path("model"));
        if (model == null) {
            model = textOrNull(params.path("tts").path("model"));
        }
        final String resolvedModel = model;
        if (credentialId == null) {
            return new ProviderInfo(null, providerFromParams, null, resolvedModel);
        }
        return credentialRepository.findByIdAndUserId(credentialId, userId)
                .map(credential -> new ProviderInfo(credentialId, credential.getProvider(), credential.getAlias(), resolvedModel))
                .orElseGet(() -> new ProviderInfo(credentialId, providerFromParams, null, resolvedModel));
    }

    private Map<UUID, AiUsageLedgerEntity> loadUsageByJobId(List<AiJobEntity> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return Map.of();
        }
        List<UUID> jobIds = jobs.stream()
                .map(AiJobEntity::getJobId)
                .filter(Objects::nonNull)
                .toList();
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AiUsageLedgerEntity> result = new java.util.LinkedHashMap<>();
        for (AiUsageLedgerEntity usage : usageLedgerRepository.findByJobIdInOrderByCreatedAtDesc(jobIds)) {
            if (usage.getJobId() != null) {
                result.putIfAbsent(usage.getJobId(), usage);
            }
        }
        return result;
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
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

    private String textOrNull(JsonNode node) {
        if (node != null && node.isTextual()) {
            String value = node.asText().trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "";
        }
        String normalized = provider.trim().toLowerCase(java.util.Locale.ROOT);
        if ("local-openai".equals(normalized)) {
            return "ollama";
        }
        return normalized;
    }

    private record ProviderInfo(UUID credentialId, String provider, String alias, String model) {
    }

    private record RetryItem(UUID cardId, String status) {
    }
}
