package app.mnema.importer.service;

import app.mnema.importer.client.core.CoreApiClient;
import app.mnema.importer.client.core.CoreCardTemplateResponse;
import app.mnema.importer.client.core.CoreFieldTemplate;
import app.mnema.importer.client.core.CorePageResponse;
import app.mnema.importer.client.core.CorePublicDeckResponse;
import app.mnema.importer.client.core.CoreUserCardResponse;
import app.mnema.importer.client.core.CoreUserDeckResponse;
import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.client.media.MediaResolved;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.domain.ImportJobStatus;
import app.mnema.importer.domain.ImportJobType;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.repository.ImportJobRepository;
import app.mnema.importer.service.parser.MnemaPackageManifest;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportProcessorTest {

    @Mock
    private CoreApiClient coreApiClient;

    @Mock
    private MediaApiClient mediaApiClient;

    @Mock
    private ImportJobRepository jobRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void processRejectsMissingUserDeckIdAndUnsupportedFormat() {
        ExportProcessor processor = new ExportProcessor(coreApiClient, mediaApiClient, jobRepository, objectMapper, 50);

        ImportJobEntity missingDeck = exportJob(ImportSourceType.csv);
        missingDeck.setTargetDeckId(null);

        ResponseStatusException missingDeckEx = assertThrows(ResponseStatusException.class, () -> processor.process(missingDeck));
        assertThat(missingDeckEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingDeckEx.getReason()).isEqualTo("userDeckId is required for export");

        ImportJobEntity unsupported = exportJob(ImportSourceType.apkg);
        ResponseStatusException unsupportedEx = assertThrows(ResponseStatusException.class, () -> processor.process(unsupported));
        assertThat(unsupportedEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unsupportedEx.getReason()).isEqualTo("Unsupported export format");
    }

    @Test
    void processExportsCsvZipAndPersistsResultMediaId() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID resultMediaId = UUID.randomUUID();
        ImportJobEntity job = exportJob(ImportSourceType.csv);
        ExportProcessor processor = new ExportProcessor(coreApiClient, mediaApiClient, jobRepository, objectMapper, 50);
        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();

        stubDeckContext(job, templateId, false);
        when(coreApiClient.getUserCards(job.getUserAccessToken(), job.getTargetDeckId(), 1, 50))
                .thenReturn(new CorePageResponse<>(List.of(card(
                        textContent("Front", "Question", "Back", "Answer")
                )), 1, 50, 1, 1, true));
        when(jobRepository.findById(job.getJobId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mediaApiClient.directUpload(eq(job.getUserId()), eq("import_file"), eq("application/zip"), eq("deck-export.zip"), anyLong(), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    try (InputStream inputStream = invocation.getArgument(5)) {
                        uploaded.write(inputStream.readAllBytes());
                    }
                    return resultMediaId;
                });

        processor.process(job);

        Map<String, String> zipEntries = unzipToTextMap(uploaded.toByteArray());
        assertThat(zipEntries.keySet()).containsExactly("deck.csv");
        assertThat(zipEntries.get("deck.csv")).contains("Front,Back").contains("Question,Answer");
        assertThat(job.getResultMediaId()).isEqualTo(resultMediaId);
        assertThat(job.getProcessedItems()).isEqualTo(1);
        assertThat(job.getTotalItems()).isEqualTo(1);
        verify(mediaApiClient, never()).resolveMap(any());
    }

    @Test
    void processExportsMnemaPackageWithManifestAndAnkiColumns() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID resultMediaId = UUID.randomUUID();
        ImportJobEntity job = exportJob(ImportSourceType.mnema);
        ExportProcessor processor = new ExportProcessor(coreApiClient, mediaApiClient, jobRepository, objectMapper, 50);
        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();

        stubDeckContext(job, templateId, true);
        when(coreApiClient.getUserCards(job.getUserAccessToken(), job.getTargetDeckId(), 1, 50))
                .thenReturn(new CorePageResponse<>(List.of(card(ankiContent())), 1, 50, 1, 1, true));
        when(jobRepository.findById(job.getJobId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mediaApiClient.directUpload(eq(job.getUserId()), eq("import_file"), eq("application/zip"), eq("deck-export.zip"), anyLong(), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    try (InputStream inputStream = invocation.getArgument(5)) {
                        uploaded.write(inputStream.readAllBytes());
                    }
                    return resultMediaId;
                });

        processor.process(job);

        Map<String, String> zipEntries = unzipToTextMap(uploaded.toByteArray());
        assertThat(zipEntries.keySet()).containsExactlyInAnyOrder("deck.csv", "deck.json", "media.json");
        assertThat(zipEntries.get("deck.csv"))
                .contains("__anki_front")
                .contains("<div>{{Front}}</div>")
                .contains("basic");

        MnemaPackageManifest manifest = objectMapper.readValue(zipEntries.get("deck.json"), MnemaPackageManifest.class);
        assertThat(manifest.deck().name()).isEqualTo("Deck");
        assertThat(manifest.template().anki()).isTrue();
        assertThat(manifest.template().fields()).hasSize(2);

        JsonNode mediaMap = objectMapper.readTree(zipEntries.get("media.json"));
        assertThat(mediaMap.isObject()).isTrue();
        assertThat(mediaMap.size()).isZero();
        assertThat(job.getResultMediaId()).isEqualTo(resultMediaId);
    }

    @Test
    void processExportsMnemaPackageWithResolvedMediaFiles() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID resultMediaId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        UUID audioId = UUID.randomUUID();
        ImportJobEntity job = exportJob(ImportSourceType.mnema);
        ExportProcessor processor = new ExportProcessor(coreApiClient, mediaApiClient, jobRepository, objectMapper, 50);
        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();

        stubDeckContext(job, templateId, true);
        when(coreApiClient.getUserCards(job.getUserAccessToken(), job.getTargetDeckId(), 1, 50))
                .thenReturn(new CorePageResponse<>(List.of(card(contentWithMedia(imageId, audioId))), 1, 50, 1, 1, true));
        when(jobRepository.findById(job.getJobId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        try (TestHttpMediaServer mediaServer = new TestHttpMediaServer()) {
            mediaServer.add("/image.png", "image/png", "png".getBytes(StandardCharsets.UTF_8));
            mediaServer.add("/audio.mp3", "audio/mpeg", "mp3".getBytes(StandardCharsets.UTF_8));
            when(mediaApiClient.resolveMap(any())).thenReturn(Map.of(
                    imageId, new MediaResolved(imageId, "card_image", mediaServer.url("/image.png"), "image/png", 3L, null, null, null, null),
                    audioId, new MediaResolved(audioId, "card_audio", mediaServer.url("/audio.mp3"), "audio/mpeg", 3L, null, null, null, null)
            ));
            when(mediaApiClient.directUpload(eq(job.getUserId()), eq("import_file"), eq("application/zip"), eq("deck-export.zip"), anyLong(), any(InputStream.class)))
                    .thenAnswer(invocation -> {
                        try (InputStream inputStream = invocation.getArgument(5)) {
                            uploaded.write(inputStream.readAllBytes());
                        }
                        return resultMediaId;
                    });

            processor.process(job);
        }

        Map<String, String> zipEntries = unzipToTextMap(uploaded.toByteArray());
        assertThat(zipEntries.keySet()).contains(
                "deck.csv",
                "deck.json",
                "media.json",
                "media/" + imageId + ".png",
                "media/" + audioId + ".mp3"
        );
        JsonNode mediaMap = objectMapper.readTree(zipEntries.get("media.json"));
        assertThat(mediaMap.path(imageId.toString()).path("fileName").asText()).isEqualTo(imageId + ".png");
        assertThat(mediaMap.path(audioId.toString()).path("mimeType").asText()).isEqualTo("audio/mpeg");
        assertThat(zipEntries.get("media/" + imageId + ".png")).isEqualTo("png");
        assertThat(zipEntries.get("media/" + audioId + ".mp3")).isEqualTo("mp3");
    }

    @Test
    void processExportsMnpkgSqlitePackageWithMediaPayloads() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID resultMediaId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        ImportJobEntity job = exportJob(ImportSourceType.mnpkg);
        ExportProcessor processor = new ExportProcessor(coreApiClient, mediaApiClient, jobRepository, objectMapper, 50);
        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();

        stubDeckContext(job, templateId, true);
        when(coreApiClient.getUserCards(job.getUserAccessToken(), job.getTargetDeckId(), 1, 50))
                .thenReturn(new CorePageResponse<>(List.of(card(contentWithSingleMedia(imageId))), 1, 50, 1, 1, true));
        when(jobRepository.findById(job.getJobId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any(ImportJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        try (TestHttpMediaServer mediaServer = new TestHttpMediaServer()) {
            mediaServer.add("/image.png", "image/png", "png".getBytes(StandardCharsets.UTF_8));
            when(mediaApiClient.resolveMap(any())).thenReturn(Map.of(
                    imageId, new MediaResolved(imageId, "card_image", mediaServer.url("/image.png"), "image/png", 3L, null, null, null, null)
            ));
            when(mediaApiClient.directUpload(eq(job.getUserId()), eq("import_file"), eq("application/vnd.mnema.package+sqlite"), eq("deck-export.mnpkg"), anyLong(), any(InputStream.class)))
                    .thenAnswer(invocation -> {
                        try (InputStream inputStream = invocation.getArgument(5)) {
                            uploaded.write(inputStream.readAllBytes());
                        }
                        return resultMediaId;
                    });

            processor.process(job);
        }

        Path sqliteFile = Files.createTempFile("mnema-export-test-", ".mnpkg");
        Files.write(sqliteFile, uploaded.toByteArray());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            try (PreparedStatement statement = connection.prepareStatement("select manifest_json from manifest");
                 ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                MnemaPackageManifest manifest = objectMapper.readValue(rs.getString(1), MnemaPackageManifest.class);
                assertThat(manifest.deck().name()).isEqualTo("Deck");
                assertThat(manifest.template().anki()).isTrue();
            }
            try (PreparedStatement statement = connection.prepareStatement("select fields_json, anki_json from cards");
                 ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                JsonNode fields = objectMapper.readTree(rs.getString("fields_json"));
                assertThat(fields.path("Front").asText()).isEqualTo(imageId.toString());
                assertThat(objectMapper.readTree(rs.getString("anki_json")).path("front").asText()).contains("mnema-media://" + imageId);
            }
            try (PreparedStatement statement = connection.prepareStatement("select file_name, mime_type, size_bytes, payload from media");
                 ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("file_name")).isEqualTo(imageId + ".png");
                assertThat(rs.getString("mime_type")).isEqualTo("image/png");
                assertThat(rs.getLong("size_bytes")).isEqualTo(3L);
                assertThat(new String(rs.getBytes("payload"), StandardCharsets.UTF_8)).isEqualTo("png");
            }
        } finally {
            Files.deleteIfExists(sqliteFile);
        }
        assertThat(job.getResultMediaId()).isEqualTo(resultMediaId);
    }

    private void stubDeckContext(ImportJobEntity job, UUID templateId, boolean ankiLayout) {
        when(coreApiClient.getUserDeck(job.getUserAccessToken(), job.getTargetDeckId()))
                .thenReturn(new CoreUserDeckResponse(
                        job.getTargetDeckId(),
                        job.getUserId(),
                        UUID.randomUUID(),
                        1,
                        2,
                        false,
                        "fsrs",
                        null,
                        "Deck",
                        "Deck description",
                        Instant.now(),
                        null,
                        false
                ));
        when(coreApiClient.getPublicDeck(anyString(), any(UUID.class), eq(2)))
                .thenReturn(new CorePublicDeckResponse(
                        UUID.randomUUID(),
                        2,
                        job.getUserId(),
                        "Deck",
                        "Deck description",
                        null,
                        templateId,
                        true,
                        true,
                        "en",
                        new String[]{"tag"},
                        Instant.now(),
                        Instant.now(),
                        Instant.now(),
                        null
                ));
        ObjectNode layout = objectMapper.createObjectNode();
        if (ankiLayout) {
            layout.put("renderMode", "anki");
        }
        when(coreApiClient.getTemplate(job.getUserAccessToken(), templateId))
                .thenReturn(new CoreCardTemplateResponse(
                        templateId,
                        job.getUserId(),
                        "Template",
                        "Template description",
                        true,
                        Instant.now(),
                        Instant.now(),
                        layout,
                        null,
                        null,
                        List.of(
                                new CoreFieldTemplate(UUID.randomUUID(), templateId, "Front", "Front", "text", true, true, 0, null, null),
                                new CoreFieldTemplate(UUID.randomUUID(), templateId, "Back", "Back", "text", true, false, 1, null, null)
                        )
                ));
    }

    private ImportJobEntity exportJob(ImportSourceType format) {
        ImportJobEntity job = new ImportJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setJobType(ImportJobType.export_job);
        job.setStatus(ImportJobStatus.queued);
        job.setUserId(UUID.randomUUID());
        job.setTargetDeckId(UUID.randomUUID());
        job.setSourceType(format);
        job.setUserAccessToken("access-token");
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        return job;
    }

    private CoreUserCardResponse card(JsonNode content) {
        return new CoreUserCardResponse(UUID.randomUUID(), UUID.randomUUID(), true, false, null, content);
    }

    private ObjectNode textContent(String firstField, String firstValue, String secondField, String secondValue) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(firstField, firstValue);
        node.put(secondField, secondValue);
        return node;
    }

    private ObjectNode ankiContent() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Front", "Question");
        node.put("Back", "Answer");
        ObjectNode anki = node.putObject("_anki");
        anki.put("front", "<div>{{Front}}</div>");
        anki.put("back", "<div>{{Back}}</div>");
        anki.put("css", ".card { color: black; }");
        anki.put("modelId", "1");
        anki.put("modelName", "Basic");
        anki.put("templateName", "basic");
        return node;
    }

    private ObjectNode contentWithMedia(UUID imageId, UUID audioId) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode image = node.putObject("Front");
        image.put("mediaId", imageId.toString());
        node.put("Back", "Listen mnema-media://" + audioId);
        ObjectNode anki = node.putObject("_anki");
        anki.put("front", "<img src=\"mnema-media://" + imageId + "\">");
        anki.put("back", "[sound:mnema-media://" + audioId + "]");
        anki.put("css", ".card{background:url(mnema-media://" + imageId + ")}");
        anki.put("modelId", "1");
        anki.put("modelName", "Basic");
        anki.put("templateName", "basic");
        return node;
    }

    private ObjectNode contentWithSingleMedia(UUID imageId) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode image = node.putObject("Front");
        image.put("mediaId", imageId.toString());
        node.put("Back", "Answer");
        ObjectNode anki = node.putObject("_anki");
        anki.put("front", "<img src=\"mnema-media://" + imageId + "\">");
        anki.put("back", "<div>{{Back}}</div>");
        anki.put("css", ".card{color:black;}");
        return node;
    }

    private Map<String, String> unzipToTextMap(byte[] zipBytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static final class TestHttpMediaServer implements AutoCloseable {
        private final HttpServer server;

        private TestHttpMediaServer() throws Exception {
            server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        }

        private void add(String path, String contentType, byte[] payload) {
            server.createContext(path, exchange -> {
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, payload.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(payload);
                }
            });
        }

        private String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
