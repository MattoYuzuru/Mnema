package app.mnema.importer.service;

import app.mnema.importer.client.core.CoreApiClient;
import app.mnema.importer.client.core.CoreCardTemplateResponse;
import app.mnema.importer.client.core.CoreFieldTemplate;
import app.mnema.importer.client.core.CorePublicDeckResponse;
import app.mnema.importer.client.core.CoreUserCardResponse;
import app.mnema.importer.client.core.CoreUserDeckResponse;
import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.client.media.MediaResolved;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportMode;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.repository.ImportJobRepository;
import app.mnema.importer.service.parser.ImportAnkiTemplate;
import app.mnema.importer.service.parser.ImportParser;
import app.mnema.importer.service.parser.ImportParserFactory;
import app.mnema.importer.service.parser.ImportRecord;
import app.mnema.importer.service.parser.ImportStream;
import app.mnema.importer.service.parser.MediaImportStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ImportProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsMergeImportWhenMappingIsEmpty() throws Exception {
        TestFixture fixture = new TestFixture();
        ImportJobEntity job = fixture.mergeJob(emptyMapping());
        fixture.stubMergeContext(job);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> fixture.processor.process(job));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("At least one field must be mapped for merge import", ex.getReason());
        verify(fixture.coreApiClient, never()).addCardsBatch(anyString(), any(), any(), any());
    }

    @Test
    void mergeImportDoesNotPersistSourceAnkiPayload() throws Exception {
        TestFixture fixture = new TestFixture();
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.put("Front", "Front");
        ImportJobEntity job = fixture.mergeJob(mapping);

        ImportAnkiTemplate ankiTemplate = new ImportAnkiTemplate(
                "model-id",
                "model-name",
                "template-name",
                "<div>{{Front}}</div>",
                "<div>{{Back}}</div>",
                ".card { color: black; }"
        );
        ImportRecord record = new ImportRecord(
                Map.of("Front", "<b>Hello</b>", "Back", "World"),
                null,
                ankiTemplate,
                0
        );
        fixture.stubMergeContext(job);
        when(fixture.stream.hasNext()).thenReturn(true, false);
        when(fixture.stream.next()).thenReturn(record);
        when(fixture.coreApiClient.addCardsBatch(anyString(), eq(job.getTargetDeckId()), any(), eq(job.getJobId())))
                .thenReturn(List.of(new CoreUserCardResponse(UUID.randomUUID(), null, true, false, null, objectMapper.createObjectNode())));

        fixture.processor.process(job);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<app.mnema.importer.client.core.CoreCreateCardRequest>> requestsCaptor =
                (ArgumentCaptor<List<app.mnema.importer.client.core.CoreCreateCardRequest>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(fixture.coreApiClient).addCardsBatch(anyString(), eq(job.getTargetDeckId()), requestsCaptor.capture(), eq(job.getJobId()));
        var request = requestsCaptor.getValue().getFirst();
        assertTrue(request.content().has("Front"));
        assertEquals("<b>Hello</b>", request.content().path("Front").asText());
        assertFalse(request.content().has("_anki"));
    }

    private ObjectNode emptyMapping() {
        return objectMapper.createObjectNode();
    }

    private class TestFixture {
        final MediaApiClient mediaApiClient = mock(MediaApiClient.class);
        final MediaDownloadService downloadService = mock(MediaDownloadService.class);
        final ImportParserFactory parserFactory = mock(ImportParserFactory.class);
        final CoreApiClient coreApiClient = mock(CoreApiClient.class);
        final ImportJobRepository jobRepository = mock(ImportJobRepository.class);
        final ImportParser parser = mock(ImportParser.class);
        final ImportStream stream = mock(ImportStream.class, withSettings().extraInterfaces(MediaImportStream.class));

        final ImportProcessor processor = new ImportProcessor(
                mediaApiClient,
                downloadService,
                parserFactory,
                coreApiClient,
                jobRepository,
                objectMapper,
                200,
                "en"
        );

        ImportJobEntity mergeJob(ObjectNode mapping) {
            UUID userId = UUID.randomUUID();
            UUID sourceMediaId = UUID.randomUUID();
            UUID targetDeckId = UUID.randomUUID();
            ImportJobEntity job = new ImportJobEntity();
            job.setJobId(UUID.randomUUID());
            job.setUserId(userId);
            job.setMode(ImportMode.merge_into_existing);
            job.setSourceType(ImportSourceType.mnpkg);
            job.setSourceMediaId(sourceMediaId);
            job.setTargetDeckId(targetDeckId);
            job.setFieldMapping(mapping);
            job.setUserAccessToken("test-access-token");
            return job;
        }

        void stubMergeContext(ImportJobEntity job) throws Exception {
            UUID publicDeckId = UUID.randomUUID();
            UUID templateId = UUID.randomUUID();

            when(mediaApiClient.resolve(List.of(job.getSourceMediaId())))
                    .thenReturn(List.of(new MediaResolved(
                            job.getSourceMediaId(),
                            "import_file",
                            "https://example.test/source.mnpkg",
                            "application/vnd.mnema.package+sqlite",
                            128L,
                            null,
                            null,
                            null,
                            null
                    )));
            when(downloadService.openStream("https://example.test/source.mnpkg"))
                    .thenReturn(new ByteArrayInputStream(new byte[0]));
            when(parserFactory.create(ImportSourceType.mnpkg)).thenReturn(parser);
            when(parser.openStream(any())).thenReturn(stream);
            when(stream.fields()).thenReturn(List.of("Front", "Back"));
            when(stream.layout()).thenReturn(null);
            when(stream.isAnki()).thenReturn(true);
            when(stream.totalItems()).thenReturn(1);

            when(coreApiClient.getUserDeck("test-access-token", job.getTargetDeckId()))
                    .thenReturn(new CoreUserDeckResponse(
                            job.getTargetDeckId(),
                            job.getUserId(),
                            publicDeckId,
                            1,
                            1,
                            false,
                            "fsrs",
                            null,
                            "Deck",
                            "Deck",
                            Instant.now(),
                            null,
                            false
                    ));
            when(coreApiClient.getPublicDeck("test-access-token", publicDeckId, 1))
                    .thenReturn(new CorePublicDeckResponse(
                            publicDeckId,
                            1,
                            job.getUserId(),
                            "Deck",
                            "Deck",
                            null,
                            templateId,
                            true,
                            true,
                            "en",
                            null,
                            Instant.now(),
                            Instant.now(),
                            Instant.now(),
                            null
                    ));
            when(coreApiClient.getTemplate("test-access-token", templateId))
                    .thenReturn(new CoreCardTemplateResponse(
                            templateId,
                            job.getUserId(),
                            "Template",
                            "Template",
                            true,
                            Instant.now(),
                            Instant.now(),
                            objectMapper.createObjectNode(),
                            null,
                            null,
                            List.of(new CoreFieldTemplate(
                                    UUID.randomUUID(),
                                    templateId,
                                    "Front",
                                    "Front",
                                    "text",
                                    true,
                                    true,
                                    0,
                                    null,
                                    null
                            ))
                    ));
            when(jobRepository.findById(any())).thenReturn(Optional.empty());
        }
    }
}
