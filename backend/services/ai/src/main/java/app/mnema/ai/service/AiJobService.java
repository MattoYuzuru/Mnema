package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.AiJobResultResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiJobRepository;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
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
    private final ObjectMapper objectMapper;

    public AiJobService(AiJobRepository jobRepository,
                        CurrentUserProvider currentUserProvider,
                        AiQuotaService quotaService,
                        AiProviderCredentialRepository credentialRepository,
                        ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.currentUserProvider = currentUserProvider;
        this.quotaService = quotaService;
        this.credentialRepository = credentialRepository;
        this.objectMapper = objectMapper;
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
        int estimatedTokens = estimateTokens(jobType, request.deckId(), params);

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
    public java.util.List<AiJobResponse> listJobs(Jwt jwt, UUID deckId, int limit) {
        UUID userId = requireUserId(jwt);
        if (deckId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deckId is required");
        }
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jobRepository
                .findByUserIdAndDeckIdOrderByCreatedAtDesc(userId, deckId, PageRequest.of(0, safeLimit))
                .map(this::toResponse)
                .getContent();
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
        return new AiJobResultResponse(job.getJobId(), job.getStatus(), job.getResultSummary());
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

    private int estimateTokens(AiJobType type, UUID deckId, JsonNode params) {
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

    private AiJobResponse toResponse(AiJobEntity job) {
        ProviderInfo providerInfo = resolveProviderInfo(job);
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
                providerInfo.model()
        );
    }

    private ProviderInfo resolveProviderInfo(AiJobEntity job) {
        JsonNode params = job.getParamsJson();
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
        return credentialRepository.findByIdAndUserId(credentialId, job.getUserId())
                .map(credential -> new ProviderInfo(credentialId, credential.getProvider(), credential.getAlias(), resolvedModel))
                .orElseGet(() -> new ProviderInfo(credentialId, providerFromParams, null, resolvedModel));
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

    private record ProviderInfo(UUID credentialId, String provider, String alias, String model) {
    }
}
