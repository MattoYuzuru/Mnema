package app.mnema.importer.service;

import app.mnema.importer.controller.dto.CreateImportJobRequest;
import app.mnema.importer.controller.dto.ImportJobResponse;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.repository.ImportJobRepository;
import app.mnema.importer.security.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class ImportJobService {

    private final ImportJobRepository jobRepository;
    private final CurrentUserProvider currentUserProvider;

    public ImportJobService(ImportJobRepository jobRepository, CurrentUserProvider currentUserProvider) {
        this.jobRepository = jobRepository;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public ImportJobResponse createJob(Jwt jwt, CreateImportJobRequest request) {
        UUID userId = requireUserId(jwt);

        ImportJobEntity job = new ImportJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setUserId(userId);
        job.setSourceType(request.sourceType());
        job.setSourceLocation(request.sourceLocation().trim());
        job.setSourceName(normalizeOptional(request.sourceName()));
        job.setSourceSizeBytes(request.sourceSizeBytes());
        job.setTargetDeckId(request.targetDeckId());
        job.setMode(defaultMode(request.mode()));
        job.setStatus(ImportJobStatus.queued);
        job.setTotalItems(null);
        job.setProcessedItems(0);
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

    private ImportJobResponse toResponse(ImportJobEntity job) {
        return new ImportJobResponse(
                job.getJobId(),
                job.getStatus(),
                job.getSourceType(),
                job.getSourceName(),
                job.getSourceLocation(),
                job.getSourceSizeBytes(),
                job.getTargetDeckId(),
                job.getMode(),
                job.getTotalItems(),
                job.getProcessedItems(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getErrorMessage()
        );
    }
}
