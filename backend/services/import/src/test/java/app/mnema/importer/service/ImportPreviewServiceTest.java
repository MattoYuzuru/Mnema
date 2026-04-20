package app.mnema.importer.service;

import app.mnema.importer.client.core.CoreApiClient;
import app.mnema.importer.client.core.CoreCardTemplateResponse;
import app.mnema.importer.client.core.CoreFieldTemplate;
import app.mnema.importer.client.core.CorePublicDeckResponse;
import app.mnema.importer.client.core.CoreUserDeckResponse;
import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.client.media.MediaResolved;
import app.mnema.importer.controller.dto.ImportFieldInfo;
import app.mnema.importer.controller.dto.ImportPreviewRequest;
import app.mnema.importer.controller.dto.ImportPreviewResponse;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.service.parser.ImportParser;
import app.mnema.importer.service.parser.ImportParserFactory;
import app.mnema.importer.service.parser.ImportPreview;
import app.mnema.importer.service.parser.ImportRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportPreviewServiceTest {

    @Mock
    private MediaApiClient mediaApiClient;

    @Mock
    private MediaDownloadService downloadService;

    @Mock
    private ImportParserFactory parserFactory;

    @Mock
    private CoreApiClient coreApiClient;

    @Mock
    private ImportParser parser;

    @InjectMocks
    private ImportPreviewService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void previewBuildsSuggestedMappingAndDefaultsSampleSize() throws Exception {
        UUID mediaId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        when(mediaApiClient.resolve(List.of(mediaId))).thenReturn(List.of(new MediaResolved(
                mediaId,
                "import_file",
                "https://cdn.example/source.csv",
                "text/csv",
                20L,
                null,
                null,
                null,
                null
        )));
        when(parserFactory.create(ImportSourceType.csv)).thenReturn(parser);
        when(downloadService.openStream("https://cdn.example/source.csv")).thenReturn(new ByteArrayInputStream("csv".getBytes()));
        when(parser.preview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(new ImportPreview(
                List.of("Front text", "Back"),
                List.of(new ImportRecord(Map.of("Front text", "Q", "Back", "A"), null, null, 0))
        ));
        when(coreApiClient.getUserDeck("access-token", userDeckId)).thenReturn(new CoreUserDeckResponse(
                userDeckId, UUID.randomUUID(), publicDeckId, 1, 2, false, "fsrs", null, "Deck", "Deck", Instant.now(), null, false
        ));
        when(coreApiClient.getPublicDeck("access-token", publicDeckId, 2)).thenReturn(new CorePublicDeckResponse(
                publicDeckId, 2, UUID.randomUUID(), "Deck", "Deck", null, templateId, true, true, "en", null, Instant.now(), Instant.now(), Instant.now(), null
        ));
        when(coreApiClient.getTemplate("access-token", templateId)).thenReturn(new CoreCardTemplateResponse(
                templateId,
                UUID.randomUUID(),
                "Template",
                "Template",
                true,
                Instant.now(),
                Instant.now(),
                objectMapper.createObjectNode(),
                null,
                null,
                List.of(
                        field(templateId, "FrontText", "text"),
                        field(templateId, "Back", "text")
                )
        ));

        ImportPreviewResponse response = service.preview("access-token", new ImportPreviewRequest(mediaId, ImportSourceType.csv, userDeckId, 0));

        assertThat(response.sourceFields()).containsExactly(
                new ImportFieldInfo("Front text", "text"),
                new ImportFieldInfo("Back", "text")
        );
        assertThat(response.targetFields()).containsExactly(
                new ImportFieldInfo("FrontText", "text"),
                new ImportFieldInfo("Back", "text")
        );
        assertThat(response.suggestedMapping()).containsEntry("FrontText", "Front text").containsEntry("Back", "Back");
        assertThat(response.sample()).containsExactly(Map.of("Front text", "Q", "Back", "A"));
        verify(parser).preview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3));
    }

    @Test
    void previewInfersSourceFieldTypes() throws Exception {
        UUID mediaId = UUID.randomUUID();
        when(mediaApiClient.resolve(List.of(mediaId))).thenReturn(List.of(new MediaResolved(
                mediaId,
                "import_file",
                "https://cdn.example/source.csv",
                "text/csv",
                20L,
                null,
                null,
                null,
                null
        )));
        when(parserFactory.create(ImportSourceType.csv)).thenReturn(parser);
        when(downloadService.openStream("https://cdn.example/source.csv")).thenReturn(new ByteArrayInputStream("csv".getBytes()));
        when(parser.preview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(2))).thenReturn(new ImportPreview(
                List.of("Question", "Answer markdown", "Image"),
                List.of()
        ));

        ImportPreviewResponse response = service.preview("token", new ImportPreviewRequest(mediaId, ImportSourceType.csv, null, 2));

        assertThat(response.sourceFields()).containsExactly(
                new ImportFieldInfo("Question", "text"),
                new ImportFieldInfo("Answer markdown", "text"),
                new ImportFieldInfo("Image", "image")
        );
    }

    @Test
    void previewRejectsMissingOrUnreadySourceMedia() {
        UUID mediaId = UUID.randomUUID();
        when(mediaApiClient.resolve(List.of(mediaId))).thenReturn(List.of());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.preview("token", new ImportPreviewRequest(mediaId, ImportSourceType.csv, null, 1)));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        when(mediaApiClient.resolve(List.of(mediaId))).thenReturn(List.of(new MediaResolved(
                mediaId, "import_file", null, "text/csv", 10L, null, null, null, null
        )));

        ResponseStatusException notReady = assertThrows(ResponseStatusException.class,
                () -> service.preview("token", new ImportPreviewRequest(mediaId, ImportSourceType.csv, null, 1)));
        assertThat(notReady.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notReady.getReason()).isEqualTo("Import source not ready");
    }

    @Test
    void previewWrapsParserIoFailure() throws Exception {
        UUID mediaId = UUID.randomUUID();
        when(mediaApiClient.resolve(List.of(mediaId))).thenReturn(List.of(new MediaResolved(
                mediaId, "import_file", "https://cdn.example/source.csv", "text/csv", 10L, null, null, null, null
        )));
        when(parserFactory.create(ImportSourceType.csv)).thenReturn(parser);
        when(downloadService.openStream("https://cdn.example/source.csv")).thenThrow(new IOException("broken stream"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.preview("token", new ImportPreviewRequest(mediaId, ImportSourceType.csv, null, 2)));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("Failed to read import source: broken stream");
    }

    @Test
    void previewRequiresAccessTokenForTargetDeckTemplate() throws Exception {
        UUID mediaId = UUID.randomUUID();
        when(mediaApiClient.resolve(List.of(mediaId))).thenReturn(List.of(new MediaResolved(
                mediaId, "import_file", "https://cdn.example/source.csv", "text/csv", 10L, null, null, null, null
        )));
        when(parserFactory.create(ImportSourceType.csv)).thenReturn(parser);
        when(downloadService.openStream("https://cdn.example/source.csv")).thenReturn(new ByteArrayInputStream("csv".getBytes()));
        when(parser.preview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(2))).thenReturn(new ImportPreview(List.of("Front"), List.of()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.preview(" ", new ImportPreviewRequest(mediaId, ImportSourceType.csv, UUID.randomUUID(), 2)));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo("Missing access token");
    }

    @Test
    void previewRejectsDeckWithoutTemplateBacking() throws Exception {
        UUID mediaId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        when(mediaApiClient.resolve(List.of(mediaId))).thenReturn(List.of(new MediaResolved(
                mediaId, "import_file", "https://cdn.example/source.csv", "text/csv", 10L, null, null, null, null
        )));
        when(parserFactory.create(ImportSourceType.csv)).thenReturn(parser);
        when(downloadService.openStream("https://cdn.example/source.csv")).thenReturn(new ByteArrayInputStream("csv".getBytes()));
        when(parser.preview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(2))).thenReturn(new ImportPreview(List.of("Front"), List.of()));
        when(coreApiClient.getUserDeck("access-token", userDeckId)).thenReturn(new CoreUserDeckResponse(
                userDeckId, UUID.randomUUID(), null, 1, 1, false, "fsrs", null, "Deck", "Deck", Instant.now(), null, false
        ));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.preview("access-token", new ImportPreviewRequest(mediaId, ImportSourceType.csv, userDeckId, 2)));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("Deck is not backed by public template");
    }

    private CoreFieldTemplate field(UUID templateId, String name, String fieldType) {
        return new CoreFieldTemplate(UUID.randomUUID(), templateId, name, name, fieldType, true, true, 0, null, null);
    }
}
