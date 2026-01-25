package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.AiJobResultResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiJobRepository;
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

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AiJobService {

    private final AiJobRepository jobRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AiQuotaService quotaService;
    private final ObjectMapper objectMapper;

    public AiJobService(AiJobRepository jobRepository,
                        CurrentUserProvider currentUserProvider,
                        AiQuotaService quotaService,
                        ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.currentUserProvider = currentUserProvider;
        this.quotaService = quotaService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiJobResponse createJob(Jwt jwt, CreateAiJobRequest request) {
        UUID userId = requireUserId(jwt);
        UUID requestId = requireRequestId(request.requestId());

        Optional<AiJobEntity> existing = jobRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            AiJobEntity job = existing.get();
            if (!job.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used");
            }
            return toResponse(job);
        }

        Instant now = Instant.now();
        AiJobType jobType = defaultType(request.type());
        JsonNode params = request.params() == null ? NullNode.getInstance() : request.params();
        int estimatedTokens = estimateTokens(jobType, request.deckId(), params);
        quotaService.consumeTokens(userId, estimatedTokens);

        AiJobEntity job = new AiJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setRequestId(requestId);
        job.setUserId(userId);
        job.setDeckId(request.deckId());
        job.setType(jobType);
        job.setStatus(AiJobStatus.queued);
        job.setProgress(0);
        job.setParamsJson(params);
        job.setInputHash(resolveInputHash(request.inputHash(), job.getType(), job.getDeckId(), params));
        job.setResultSummary(NullNode.getInstance());
        job.setAttempts(0);
        job.setNextRunAt(null);
        job.setErrorMessage(null);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        try {
            AiJobEntity saved = jobRepository.save(job);
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            AiJobEntity duplicate = jobRepository.findByRequestId(requestId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used"));
            if (!duplicate.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request id already used");
            }
            return toResponse(duplicate);
        }
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
                job.getErrorMessage()
        );
    }
}
