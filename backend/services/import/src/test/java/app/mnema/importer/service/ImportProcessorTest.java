package app.mnema.importer.service;

import app.mnema.importer.client.core.CoreApiClient;
import app.mnema.importer.client.core.CoreCardTemplateRequest;
import app.mnema.importer.client.core.CoreCardTemplateResponse;
import app.mnema.importer.client.core.CoreCardProgressRequest;
import app.mnema.importer.client.core.CoreCreateCardRequest;
import app.mnema.importer.client.core.CorePublicDeckRequest;
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
import app.mnema.importer.service.parser.ImportLayout;
import app.mnema.importer.service.parser.ImportMedia;
import app.mnema.importer.service.parser.ImportParser;
import app.mnema.importer.service.parser.ImportParserFactory;
import app.mnema.importer.service.parser.ImportRecord;
import app.mnema.importer.service.parser.ImportRecordProgress;
import app.mnema.importer.service.parser.ImportStream;
import app.mnema.importer.service.parser.MediaImportStream;
import app.mnema.importer.service.parser.TemplateAwareImportStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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

    @Test
    void createNewImportBuildsTemplateDeckAndSeedsProgress() throws Exception {
        TestFixture fixture = new TestFixture();
        ImportJobEntity job = fixture.createNewJob();
        job.setSourceName("imported.csv");
        fixture.stubSource(job);
        when(fixture.stream.fields()).thenReturn(List.of("Prompt", "Answer"));
        when(fixture.stream.layout()).thenReturn(new ImportLayout(List.of("Prompt"), List.of("Answer")));
        when(fixture.stream.isAnki()).thenReturn(false);
        when(fixture.stream.totalItems()).thenReturn(2);

        ImportRecord placeholder = new ImportRecord(
                Map.of("Prompt", "Please update to the latest Anki version"),
                null,
                null,
                0
        );
        ImportRecord actual = new ImportRecord(
                Map.of("Prompt", "  <img src=\"pic.png\">Question  ", "Answer", " [sound:voice.mp3]Answer "),
                new ImportRecordProgress(2.5, 0.41, 3, false),
                null,
                1
        );
        when(fixture.stream.hasNext()).thenReturn(true, true, false);
        when(fixture.stream.next()).thenReturn(placeholder, actual);

        UUID templateId = UUID.randomUUID();
        UUID createdDeckId = UUID.randomUUID();
        when(fixture.coreApiClient.createTemplate(anyString(), any())).thenReturn(new CoreCardTemplateResponse(
                templateId,
                job.getUserId(),
                "Template",
                "Template",
                false,
                Instant.now(),
                Instant.now(),
                objectMapper.createObjectNode(),
                null,
                null,
                List.of(
                        new CoreFieldTemplate(UUID.randomUUID(), templateId, "Prompt", "Prompt", "text", true, true, 0, null, null),
                        new CoreFieldTemplate(UUID.randomUUID(), templateId, "Answer", "Answer", "text", true, false, 1, null, null)
                )
        ));
        when(fixture.coreApiClient.createDeck(anyString(), any())).thenReturn(new CoreUserDeckResponse(
                createdDeckId,
                job.getUserId(),
                UUID.randomUUID(),
                1,
                1,
                false,
                "fsrs",
                null,
                "imported.csv",
                "Imported from csv",
                Instant.now(),
                null,
                false
        ));
        when(fixture.coreApiClient.addCardsBatch(anyString(), eq(createdDeckId), any(), eq(job.getJobId())))
                .thenReturn(List.of(new CoreUserCardResponse(UUID.randomUUID(), null, true, false, null, objectMapper.createObjectNode())));

        fixture.processor.process(job);

        ArgumentCaptor<CoreCardTemplateRequest> templateCaptor = ArgumentCaptor.forClass(app.mnema.importer.client.core.CoreCardTemplateRequest.class);
        verify(fixture.coreApiClient).createTemplate(anyString(), templateCaptor.capture());
        var templateRequest = templateCaptor.getValue();
        assertEquals("imported.csv", templateRequest.name());
        assertEquals("Imported from csv", templateRequest.description());
        assertEquals(List.of("Prompt", "Answer"), templateRequest.fields().stream().map(CoreFieldTemplate::name).toList());
        assertTrue(templateRequest.fields().getFirst().isOnFront());
        assertFalse(templateRequest.fields().getLast().isOnFront());

        ArgumentCaptor<CorePublicDeckRequest> deckCaptor = ArgumentCaptor.forClass(CorePublicDeckRequest.class);
        verify(fixture.coreApiClient).createDeck(anyString(), deckCaptor.capture());
        assertEquals("imported.csv", deckCaptor.getValue().name());
        assertEquals("Imported from csv", deckCaptor.getValue().description());
        assertEquals("en", deckCaptor.getValue().language());
        assertFalse(deckCaptor.getValue().isListed());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoreCreateCardRequest>> requestsCaptor =
                (ArgumentCaptor<List<CoreCreateCardRequest>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(fixture.coreApiClient).addCardsBatch(anyString(), eq(createdDeckId), requestsCaptor.capture(), eq(job.getJobId()));
        CoreCreateCardRequest request = requestsCaptor.getValue().getFirst();
        assertEquals("Question", request.content().path("Prompt").asText());
        assertEquals("Answer", request.content().path("Answer").asText());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoreCardProgressRequest>> progressCaptor =
                (ArgumentCaptor<List<CoreCardProgressRequest>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(fixture.coreApiClient).seedProgress(eq("test-access-token"), eq(createdDeckId), progressCaptor.capture());
        CoreCardProgressRequest progress = progressCaptor.getValue().getFirst();
        assertEquals(0.41, progress.difficulty01());
        assertEquals(2.5, progress.stabilityDays());
        assertEquals(3, progress.reviewCount());
        assertTrue(progress.nextReviewAt().isAfter(progress.lastReviewAt()));
        assertEquals(createdDeckId, job.getTargetDeckId());
        assertEquals(1, job.getProcessedItems());
        assertEquals(2, job.getTotalItems());
    }

    @Test
    void createNewAnkiImportRendersAndUploadsMedia() throws Exception {
        TestFixture fixture = new TestFixture();
        ImportJobEntity job = fixture.createNewJob();
        job.setSourceType(ImportSourceType.mnpkg);
        fixture.stubSource(job);
        when(fixture.stream.fields()).thenReturn(List.of("Front", "Back"));
        when(fixture.stream.layout()).thenReturn(new ImportLayout(List.of("Front"), List.of("Back")));
        when(fixture.stream.isAnki()).thenReturn(true);
        ImportAnkiTemplate template = new ImportAnkiTemplate(
                "model-id",
                "Basic",
                "Card 1",
                "<div onload=\"evil()\">{{furigana:Front}}</div>[sound:voice.mp3]<img src=\"image.png\"><script>alert(1)</script>",
                "{{FrontSide}} {{cloze:Back}}",
                "@font-face { src:url(font.woff2); } body { background:url('image.png'); } .card { color:red; }"
        );
        when(fixture.stream.ankiTemplate()).thenReturn(template);
        when(fixture.stream.hasNext()).thenReturn(true, false);
        when(fixture.stream.next()).thenReturn(new ImportRecord(
                Map.of(
                        "Front", "漢字[かんじ;1]",
                        "Back", "{{c1::secret}}"
                ),
                new ImportRecordProgress(1.2, 0.52, 4, false),
                template,
                0
        ));
        when(((MediaImportStream) fixture.stream).openMedia("image.png"))
                .thenReturn(new ImportMedia(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
        when(((MediaImportStream) fixture.stream).openMedia("voice.mp3"))
                .thenReturn(new ImportMedia(new ByteArrayInputStream(new byte[]{4, 5, 6}), 3));

        UUID templateId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        UUID imageMediaId = UUID.randomUUID();
        UUID audioMediaId = UUID.randomUUID();
        when(fixture.coreApiClient.createTemplate(anyString(), any())).thenReturn(new CoreCardTemplateResponse(
                templateId,
                job.getUserId(),
                "Template",
                "Template",
                false,
                Instant.now(),
                Instant.now(),
                objectMapper.createObjectNode(),
                null,
                null,
                List.of(
                        new CoreFieldTemplate(UUID.randomUUID(), templateId, "Front", "Front", "text", true, true, 0, null, null),
                        new CoreFieldTemplate(UUID.randomUUID(), templateId, "Back", "Back", "text", true, false, 1, null, null)
                )
        ));
        when(fixture.coreApiClient.createDeck(anyString(), any())).thenReturn(new CoreUserDeckResponse(
                userDeckId,
                job.getUserId(),
                UUID.randomUUID(),
                1,
                1,
                false,
                "fsrs",
                null,
                "Imported deck",
                "Imported from mnpkg",
                Instant.now(),
                null,
                false
        ));
        when(fixture.mediaApiClient.directUpload(eq(job.getUserId()), eq("card_image"), anyString(), eq("image.png"), eq(3L), any()))
                .thenReturn(imageMediaId);
        when(fixture.mediaApiClient.directUpload(eq(job.getUserId()), eq("card_audio"), anyString(), eq("voice.mp3"), eq(3L), any()))
                .thenReturn(audioMediaId);
        when(fixture.coreApiClient.addCardsBatch(anyString(), eq(userDeckId), any(), eq(job.getJobId())))
                .thenReturn(List.of(new CoreUserCardResponse(UUID.randomUUID(), null, true, false, null, objectMapper.createObjectNode())));

        fixture.processor.process(job);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoreCreateCardRequest>> requestsCaptor =
                (ArgumentCaptor<List<CoreCreateCardRequest>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(fixture.coreApiClient).addCardsBatch(anyString(), eq(userDeckId), requestsCaptor.capture(), eq(job.getJobId()));
        ObjectNode content = (ObjectNode) requestsCaptor.getValue().getFirst().content();
        assertEquals("漢字[かんじ;1]", content.path("Front").asText());
        assertEquals("{{c1::secret}}", content.path("Back").asText());
        assertTrue(content.has("_anki"));
        String frontHtml = content.path("_anki").path("front").asText();
        String backHtml = content.path("_anki").path("back").asText();
        String css = content.path("_anki").path("css").asText();
        assertTrue(frontHtml.contains("<ruby>漢字<rt>かんじ</rt></ruby>"));
        assertTrue(frontHtml.contains("mnema-media://" + imageMediaId));
        assertTrue(frontHtml.contains("mnema-media://" + audioMediaId));
        assertFalse(frontHtml.contains("<script"));
        assertFalse(frontHtml.toLowerCase().contains("onload="));
        assertTrue(backHtml.contains("<span class=\"cloze\">secret</span>"));
        assertTrue(backHtml.contains("<audio controls src=\"mnema-media://" + audioMediaId));
        assertFalse(css.contains("@font-face"));
        assertTrue(css.contains(".anki-card"));
        assertTrue(css.contains("mnema-media://" + imageMediaId));
    }

    @Test
    void createNewImportRejectsTemplateMismatch() throws Exception {
        TestFixture fixture = new TestFixture();
        ImportJobEntity job = fixture.createNewJob();
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.put("Front", "Front");
        job.setFieldMapping(mapping);
        fixture.stubSource(job);
        when(fixture.stream.fields()).thenReturn(List.of("Front"));
        when(fixture.stream.layout()).thenReturn(new ImportLayout(List.of("Front"), List.of()));
        when(((TemplateAwareImportStream) fixture.stream).templateFields()).thenReturn(List.of(new CoreFieldTemplate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Other",
                "Other",
                "text",
                true,
                true,
                0,
                null,
                null
        )));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> fixture.processor.process(job));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Selected source fields do not match import template", ex.getReason());
    }

    @Test
    void mergeImportRejectsDeckWithoutPublicTemplate() throws Exception {
        TestFixture fixture = new TestFixture();
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.put("Front", "Front");
        ImportJobEntity job = fixture.mergeJob(mapping);
        fixture.stubSource(job);
        when(fixture.stream.fields()).thenReturn(List.of("Front"));
        when(fixture.stream.isAnki()).thenReturn(false);
        when(fixture.coreApiClient.getUserDeck("test-access-token", job.getTargetDeckId()))
                .thenReturn(new CoreUserDeckResponse(
                        job.getTargetDeckId(),
                        job.getUserId(),
                        null,
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

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> fixture.processor.process(job));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Deck is missing public template", ex.getReason());
    }

    @Test
    void rejectsMissingOrUnreadySourceMedia() {
        TestFixture fixture = new TestFixture();
        ImportJobEntity job = fixture.createNewJob();
        job.setSourceMediaId(null);

        ResponseStatusException missing = assertThrows(ResponseStatusException.class, () -> fixture.processor.process(job));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals("Missing source media", missing.getReason());

        job.setSourceMediaId(UUID.randomUUID());
        when(fixture.mediaApiClient.resolve(List.of(job.getSourceMediaId())))
                .thenReturn(List.of(new MediaResolved(
                        job.getSourceMediaId(),
                        "import_file",
                        null,
                        "text/csv",
                        10L,
                        null,
                        null,
                        null,
                        null
                )));

        ResponseStatusException notReady = assertThrows(ResponseStatusException.class, () -> fixture.processor.process(job));
        assertEquals(HttpStatus.CONFLICT, notReady.getStatusCode());
        assertEquals("Import source not ready", notReady.getReason());
    }

    @Test
    void createNewImportRejectsWhenNoFieldsAreSelected() throws Exception {
        TestFixture fixture = new TestFixture();
        ImportJobEntity job = fixture.createNewJob();
        fixture.stubSource(job);
        when(fixture.stream.fields()).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> fixture.processor.process(job));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("At least one source field must be selected", ex.getReason());
    }

    @Test
    void createNewImportHandlesUuidMediaAndUploadedAudioVideo() throws Exception {
        TestFixture fixture = new TestFixture();
        ImportJobEntity job = fixture.createNewJob();
        job.setSourceType(ImportSourceType.apkg);
        fixture.stubSource(job);
        when(fixture.stream.fields()).thenReturn(List.of("Photo", "Sound", "Video"));
        when(fixture.stream.layout()).thenReturn(new ImportLayout(List.of("Photo"), List.of("Sound", "Video")));
        when(fixture.stream.hasNext()).thenReturn(true, false);

        UUID existingMediaId = UUID.randomUUID();
        when(fixture.mediaApiClient.resolve(List.of(existingMediaId)))
                .thenReturn(List.of(new MediaResolved(
                        existingMediaId,
                        "card_image",
                        "https://cdn.example/existing.png",
                        "image/png",
                        10L,
                        null,
                        null,
                        null,
                        null
                )));
        when(((MediaImportStream) fixture.stream).openMedia("voice.mp3"))
                .thenReturn(new ImportMedia(new ByteArrayInputStream(new byte[]{1, 2}), 2));
        when(((MediaImportStream) fixture.stream).openMedia("movie.mp4"))
                .thenReturn(new ImportMedia(new ByteArrayInputStream(new byte[]{3, 4, 5}), 3));
        when(fixture.stream.next()).thenReturn(new ImportRecord(
                Map.of(
                        "Photo", existingMediaId.toString(),
                        "Sound", "[sound:voice.mp3]",
                        "Video", "<video src=\"movie.mp4\"></video>"
                ),
                null,
                null,
                0
        ));

        UUID templateId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        when(fixture.coreApiClient.createTemplate(anyString(), any())).thenReturn(new CoreCardTemplateResponse(
                templateId,
                job.getUserId(),
                "Template",
                "Template",
                false,
                Instant.now(),
                Instant.now(),
                objectMapper.createObjectNode(),
                null,
                null,
                List.of(
                        new CoreFieldTemplate(UUID.randomUUID(), templateId, "Photo", "Photo", "image", true, true, 0, null, null),
                        new CoreFieldTemplate(UUID.randomUUID(), templateId, "Sound", "Sound", "audio", true, false, 1, null, null),
                        new CoreFieldTemplate(UUID.randomUUID(), templateId, "Video", "Video", "video", false, false, 2, null, null)
                )
        ));
        when(fixture.coreApiClient.createDeck(anyString(), any())).thenReturn(new CoreUserDeckResponse(
                userDeckId,
                job.getUserId(),
                UUID.randomUUID(),
                1,
                1,
                false,
                "fsrs",
                null,
                "Imported deck",
                "Imported from apkg",
                Instant.now(),
                null,
                false
        ));
        UUID uploadedAudio = UUID.randomUUID();
        UUID uploadedVideo = UUID.randomUUID();
        when(fixture.mediaApiClient.directUpload(eq(job.getUserId()), eq("card_audio"), anyString(), eq("voice.mp3"), eq(2L), any()))
                .thenReturn(uploadedAudio);
        when(fixture.mediaApiClient.directUpload(eq(job.getUserId()), eq("card_video"), anyString(), eq("movie.mp4"), eq(3L), any()))
                .thenReturn(uploadedVideo);
        when(fixture.coreApiClient.addCardsBatch(anyString(), eq(userDeckId), any(), eq(job.getJobId())))
                .thenReturn(List.of(new CoreUserCardResponse(UUID.randomUUID(), null, true, false, null, objectMapper.createObjectNode())));

        fixture.processor.process(job);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CoreCreateCardRequest>> requestsCaptor =
                (ArgumentCaptor<List<CoreCreateCardRequest>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(fixture.coreApiClient).addCardsBatch(anyString(), eq(userDeckId), requestsCaptor.capture(), eq(job.getJobId()));
        ObjectNode content = (ObjectNode) requestsCaptor.getValue().getFirst().content();
        assertEquals(existingMediaId.toString(), content.path("Photo").path("mediaId").asText());
        assertEquals("image", content.path("Photo").path("kind").asText());
        assertEquals(uploadedAudio.toString(), content.path("Sound").path("mediaId").asText());
        assertEquals("audio", content.path("Sound").path("kind").asText());
        assertEquals(uploadedVideo.toString(), content.path("Video").path("mediaId").asText());
        assertEquals("video", content.path("Video").path("kind").asText());
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
        final ImportStream stream = mock(ImportStream.class, withSettings().extraInterfaces(MediaImportStream.class, TemplateAwareImportStream.class));

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

        ImportJobEntity createNewJob() {
            UUID userId = UUID.randomUUID();
            UUID sourceMediaId = UUID.randomUUID();
            ImportJobEntity job = new ImportJobEntity();
            job.setJobId(UUID.randomUUID());
            job.setUserId(userId);
            job.setMode(ImportMode.create_new);
            job.setSourceType(ImportSourceType.csv);
            job.setSourceMediaId(sourceMediaId);
            job.setUserAccessToken("test-access-token");
            job.setIsPublic(false);
            job.setIsListed(true);
            return job;
        }

        void stubSource(ImportJobEntity job) throws Exception {
            when(mediaApiClient.resolve(List.of(job.getSourceMediaId())))
                    .thenReturn(List.of(new MediaResolved(
                            job.getSourceMediaId(),
                            "import_file",
                            "https://example.test/source." + job.getSourceType(),
                            "application/octet-stream",
                            128L,
                            null,
                            null,
                            null,
                            null
                    )));
            when(downloadService.openStream("https://example.test/source." + job.getSourceType()))
                    .thenReturn(new ByteArrayInputStream(new byte[0]));
            when(parserFactory.create(job.getSourceType())).thenReturn(parser);
            when(parser.openStream(any())).thenReturn(stream);
            when(jobRepository.findById(any())).thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        }

        void stubMergeContext(ImportJobEntity job) throws Exception {
            UUID publicDeckId = UUID.randomUUID();
            UUID templateId = UUID.randomUUID();

            stubSource(job);
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
        }
    }
}
