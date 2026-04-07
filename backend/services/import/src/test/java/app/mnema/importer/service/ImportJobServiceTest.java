package app.mnema.importer.service;

import app.mnema.importer.controller.dto.CreateExportJobRequest;
import app.mnema.importer.controller.dto.CreateImportJobRequest;
import app.mnema.importer.controller.dto.ImportJobResponse;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportJobType;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.repository.ImportJobRepository;
import app.mnema.importer.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportJobServiceTest {

    @Mock
    private ImportJobRepository jobRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Jwt jwt;

    private ImportJobService service;

    @BeforeEach
    void setUp() {
        service = new ImportJobService(jobRepository, currentUserProvider, new ObjectMapper());
    }

    @Test
    void createImportJobNormalizesOptionalValuesAndDefaultsMode() {
        UUID userId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateImportJobRequest request = new CreateImportJobRequest(
                sourceMediaId,
                ImportSourceType.csv,
                "  deck.csv  ",
                128L,
                null,
                null,
                "  My deck  ",
                "   ",
                "  en  ",
                new String[]{"tag-1"},
                true,
                false,
                Map.of("Front", "Question")
        );

        ImportJobResponse response = service.createImportJob(jwt, "access-token", request);

        ArgumentCaptor<ImportJobEntity> saved = ArgumentCaptor.forClass(ImportJobEntity.class);
        verify(jobRepository).save(saved.capture());
        ImportJobEntity job = saved.getValue();

        assertThat(job.getJobType()).isEqualTo(ImportJobType.import_job);
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.queued);
        assertThat(job.getMode()).isEqualTo(ImportMode.create_new);
        assertThat(job.getSourceName()).isEqualTo("deck.csv");
        assertThat(job.getDeckName()).isEqualTo("My deck");
        assertThat(job.getDeckDescription()).isNull();
        assertThat(job.getLanguageCode()).isEqualTo("en");
        assertThat(job.getFieldMapping().get("Front").asText()).isEqualTo("Question");
        assertThat(job.getUserAccessToken()).isEqualTo("access-token");
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isEqualTo(job.getCreatedAt());
        assertThat(response.deckName()).isEqualTo("My deck");
        assertThat(response.mode()).isEqualTo(ImportMode.create_new);
    }

    @Test
    void createImportJobRejectsInvalidDeckMetadata() {
        when(currentUserProvider.requireUserId(jwt)).thenReturn(UUID.randomUUID());

        CreateImportJobRequest request = new CreateImportJobRequest(
                UUID.randomUUID(),
                ImportSourceType.csv,
                "deck.csv",
                1L,
                null,
                ImportMode.create_new,
                "Deck",
                "Description",
                "en",
                new String[]{"one", "two", "three", "four", "five", "six"},
                true,
                true,
                null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createImportJob(jwt, "access-token", request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("Too many tags");
    }

    @Test
    void createImportJobRequiresAccessToken() {
        when(currentUserProvider.requireUserId(jwt)).thenReturn(UUID.randomUUID());

        CreateImportJobRequest request = new CreateImportJobRequest(
                UUID.randomUUID(),
                ImportSourceType.csv,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createImportJob(jwt, "   ", request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo("Missing access token");
    }

    @Test
    void createImportJobTranslatesMissingUserToUnauthorized() {
        when(currentUserProvider.requireUserId(jwt)).thenThrow(new IllegalStateException("user_id claim missing"));

        CreateImportJobRequest request = new CreateImportJobRequest(
                UUID.randomUUID(),
                ImportSourceType.csv,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createImportJob(jwt, "access-token", request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo("user_id claim missing");
    }

    @Test
    void createExportJobPersistsQueuedExportJob() {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ImportJobResponse response = service.createExportJob(
                jwt,
                "access-token",
                new CreateExportJobRequest(userDeckId, ImportSourceType.mnpkg)
        );

        ArgumentCaptor<ImportJobEntity> saved = ArgumentCaptor.forClass(ImportJobEntity.class);
        verify(jobRepository).save(saved.capture());
        ImportJobEntity job = saved.getValue();

        assertThat(job.getJobType()).isEqualTo(ImportJobType.export_job);
        assertThat(job.getTargetDeckId()).isEqualTo(userDeckId);
        assertThat(job.getSourceType()).isEqualTo(ImportSourceType.mnpkg);
        assertThat(job.getFieldMapping()).isEqualTo(NullNode.getInstance());
        assertThat(job.getMode()).isEqualTo(ImportMode.create_new);
        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.queued);
        assertThat(response.jobType()).isEqualTo(ImportJobType.export_job);
    }

    @Test
    void getJobReturnsOwnedJob() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);
        when(jobRepository.findByJobIdAndUserId(jobId, userId)).thenReturn(Optional.of(existingJob(jobId, userId)));

        ImportJobResponse response = service.getJob(jwt, jobId);

        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.status()).isEqualTo(ImportJobStatus.completed);
        assertThat(response.resultMediaId()).isNotNull();
    }

    @Test
    void getJobRejectsForeignOrMissingJob() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);
        when(jobRepository.findByJobIdAndUserId(jobId, userId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.getJob(jwt, jobId));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Import job not found");
    }

    private ImportJobEntity existingJob(UUID jobId, UUID userId) {
        ImportJobEntity job = new ImportJobEntity();
        job.setJobId(jobId);
        job.setJobType(ImportJobType.import_job);
        job.setUserId(userId);
        job.setTargetDeckId(UUID.randomUUID());
        job.setSourceType(ImportSourceType.mnema);
        job.setSourceName("deck.zip");
        job.setSourceLocation("s3://deck.zip");
        job.setSourceSizeBytes(42L);
        job.setSourceMediaId(UUID.randomUUID());
        job.setMode(ImportMode.merge_into_existing);
        job.setStatus(ImportJobStatus.completed);
        job.setTotalItems(10);
        job.setProcessedItems(10);
        job.setFieldMapping(new ObjectMapper().valueToTree(Map.of("Front", "Question")));
        job.setDeckName("Deck");
        job.setDeckDescription("Description");
        job.setLanguageCode("en");
        job.setTags(new String[]{"tag"});
        job.setIsPublic(true);
        job.setIsListed(false);
        job.setResultMediaId(UUID.randomUUID());
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        job.setStartedAt(Instant.now());
        job.setCompletedAt(Instant.now());
        return job;
    }
}
