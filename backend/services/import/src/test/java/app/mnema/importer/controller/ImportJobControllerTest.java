package app.mnema.importer.controller;

import app.mnema.importer.controller.dto.CreateExportJobRequest;
import app.mnema.importer.controller.dto.CreateImportJobRequest;
import app.mnema.importer.controller.dto.ImportJobResponse;
import app.mnema.importer.controller.dto.ImportPreviewRequest;
import app.mnema.importer.controller.dto.ImportPreviewResponse;
import app.mnema.importer.controller.dto.UploadImportSourceResponse;
import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportJobType;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.service.ImportJobService;
import app.mnema.importer.service.ImportPreviewService;
import app.mnema.importer.service.ImportSourceService;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportJobControllerTest {

    @Mock
    private ImportJobService jobService;

    @Mock
    private ImportSourceService sourceService;

    @Mock
    private ImportPreviewService previewService;

    @Mock
    private Jwt jwt;

    @Test
    void delegatesUploadPreviewAndJobOperations() {
        ImportJobController controller = new ImportJobController(jobService, sourceService, previewService);
        UUID jobId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "deck.csv", "text/csv", "Front,Back".getBytes());
        UploadImportSourceResponse uploadResponse = new UploadImportSourceResponse(UUID.randomUUID(), "deck.csv", 10L, ImportSourceType.csv);
        ImportPreviewRequest previewRequest = new ImportPreviewRequest(UUID.randomUUID(), ImportSourceType.csv, UUID.randomUUID(), 3);
        ImportPreviewResponse previewResponse = new ImportPreviewResponse(List.of(), List.of(), Map.of(), List.of());
        CreateImportJobRequest importRequest = new CreateImportJobRequest(
                UUID.randomUUID(), ImportSourceType.csv, "deck.csv", 10L, UUID.randomUUID(), ImportMode.create_new,
                "Deck", "Deck", "en", new String[]{"tag"}, true, true, Map.of("Front", "Question")
        );
        CreateExportJobRequest exportRequest = new CreateExportJobRequest(UUID.randomUUID(), ImportSourceType.mnpkg);
        ImportJobResponse jobResponse = new ImportJobResponse(
                jobId,
                ImportJobType.import_job,
                ImportJobStatus.queued,
                ImportSourceType.csv,
                "deck.csv",
                null,
                10L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ImportMode.create_new,
                null,
                0,
                NullNode.getInstance(),
                "Deck",
                "Deck",
                "en",
                new String[]{"tag"},
                true,
                true,
                null,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null
        );

        when(jwt.getTokenValue()).thenReturn("access-token");
        when(sourceService.uploadSource(jwt, file, ImportSourceType.csv)).thenReturn(uploadResponse);
        when(previewService.preview("access-token", previewRequest)).thenReturn(previewResponse);
        when(jobService.createImportJob(jwt, "access-token", importRequest)).thenReturn(jobResponse);
        when(jobService.createExportJob(jwt, "access-token", exportRequest)).thenReturn(jobResponse);
        when(jobService.getJob(jwt, jobId)).thenReturn(jobResponse);

        assertThat(controller.upload(jwt, ImportSourceType.csv, file)).isEqualTo(uploadResponse);
        assertThat(controller.preview(jwt, previewRequest)).isEqualTo(previewResponse);
        assertThat(controller.createImportJob(jwt, importRequest)).isEqualTo(jobResponse);
        assertThat(controller.createExportJob(jwt, exportRequest)).isEqualTo(jobResponse);
        assertThat(controller.getJob(jwt, jobId)).isEqualTo(jobResponse);

        verify(sourceService).uploadSource(jwt, file, ImportSourceType.csv);
        verify(previewService).preview("access-token", previewRequest);
        verify(jobService).createImportJob(jwt, "access-token", importRequest);
        verify(jobService).createExportJob(jwt, "access-token", exportRequest);
        verify(jobService).getJob(jwt, jobId);
    }
}
