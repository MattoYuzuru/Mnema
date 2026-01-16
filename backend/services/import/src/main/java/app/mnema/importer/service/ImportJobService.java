package app.mnema.importer.service;

import app.mnema.importer.controller.dto.CreateExportJobRequest;
import app.mnema.importer.controller.dto.CreateImportJobRequest;
import app.mnema.importer.controller.dto.ImportJobResponse;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportJobType;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.repository.ImportJobRepository;
import app.mnema.importer.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ImportJobService {

    private static final int MAX_DECK_NAME = 50;
    private static final int MAX_DECK_DESCRIPTION = 200;
    private static final int MAX_TAGS = 5;
    private static final int MAX_TAG_LENGTH = 25;

    private final ImportJobRepository jobRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    public ImportJobService(ImportJobRepository jobRepository,
                            CurrentUserProvider currentUserProvider,
                            ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImportJobResponse createImportJob(Jwt jwt, String accessToken, CreateImportJobRequest request) {
        UUID userId = requireUserId(jwt);

        ImportJobEntity job = new ImportJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setJobType(ImportJobType.import_job);
        job.setUserId(userId);
        job.setSourceType(request.sourceType());
        job.setSourceLocation(null);
        job.setSourceName(normalizeOptional(request.sourceName()));
        job.setSourceSizeBytes(request.sourceSizeBytes());
        job.setSourceMediaId(request.sourceMediaId());
        job.setTargetDeckId(request.targetDeckId());
        job.setMode(defaultMode(request.mode()));
        job.setStatus(ImportJobStatus.queued);
        job.setTotalItems(null);
        job.setProcessedItems(0);
        job.setFieldMapping(toMappingNode(request.fieldMapping()));
        job.setDeckName(normalizeOptional(request.deckName()));
        job.setDeckDescription(normalizeOptional(request.deckDescription()));
        job.setLanguageCode(normalizeOptional(request.language()));
        job.setTags(normalizeTags(request.tags()));
        job.setIsPublic(request.isPublic());
        job.setIsListed(request.isListed());
        job.setResultMediaId(null);
        job.setUserAccessToken(requireAccessToken(accessToken));
        Instant now = Instant.now();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        validateDeckMeta(job.getDeckName(), job.getDeckDescription(), job.getTags());

        return toResponse(jobRepository.save(job));
    }

    @Transactional
    public ImportJobResponse createExportJob(Jwt jwt, String accessToken, CreateExportJobRequest request) {
        UUID userId = requireUserId(jwt);

        ImportJobEntity job = new ImportJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setJobType(ImportJobType.export_job);
        job.setUserId(userId);
        job.setSourceType(request.format());
        job.setTargetDeckId(request.userDeckId());
        job.setMode(ImportMode.create_new);
        job.setStatus(ImportJobStatus.queued);
        job.setProcessedItems(0);
        job.setTotalItems(null);
        job.setFieldMapping(NullNode.getInstance());
        job.setUserAccessToken(requireAccessToken(accessToken));
        Instant now = Instant.now();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return toResponse(jobRepository.save(job));
    }

    @Transactional(readOnly = true)
    public ImportJobResponse getJob(Jwt jwt, UUID jobId) {
        UUID userId = requireUserId(jwt);

        ImportJobEntity job = jobRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found"));

        return toResponse(job);
    }

    private UUID requireUserId(Jwt jwt) {
        try {
            return currentUserProvider.requireUserId(jwt);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
    }

    private ImportMode defaultMode(ImportMode mode) {
        return mode == null ? ImportMode.create_new : mode;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String requireAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing access token");
        }
        return accessToken;
    }

    private com.fasterxml.jackson.databind.JsonNode toMappingNode(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return NullNode.getInstance();
        }
        return objectMapper.valueToTree(mapping);
    }

    private String[] normalizeTags(String[] tags) {
        if (tags == null || tags.length == 0) {
            return null;
        }
        return tags;
    }

    private void validateDeckMeta(String name, String description, String[] tags) {
        validateLength(name, MAX_DECK_NAME, "Deck name");
        validateLength(description, MAX_DECK_DESCRIPTION, "Deck description");
        if (tags == null) {
            return;
        }
        if (tags.length > MAX_TAGS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many tags");
        }
        for (String tag : tags) {
            if (tag != null && tag.length() > MAX_TAG_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag is too long");
            }
        }
    }

    private void validateLength(String value, int maxLength, String label) {
        if (value == null) {
            return;
        }
        if (value.length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + " must be at most " + maxLength + " characters"
            );
        }
    }

    private ImportJobResponse toResponse(ImportJobEntity job) {
        return new ImportJobResponse(
                job.getJobId(),
                job.getJobType(),
                job.getStatus(),
                job.getSourceType(),
                job.getSourceName(),
                job.getSourceLocation(),
                job.getSourceSizeBytes(),
                job.getSourceMediaId(),
                job.getTargetDeckId(),
                job.getMode(),
                job.getTotalItems(),
                job.getProcessedItems(),
                job.getFieldMapping(),
                job.getDeckName(),
                job.getDeckDescription(),
                job.getLanguageCode(),
                job.getTags(),
                job.getIsPublic(),
                job.getIsListed(),
                job.getResultMediaId(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage()
        );
    }
}
