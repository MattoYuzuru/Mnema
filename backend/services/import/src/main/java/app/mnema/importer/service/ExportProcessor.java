package app.mnema.importer.service;

import app.mnema.importer.client.core.CoreApiClient;
import app.mnema.importer.client.core.CoreCardTemplateResponse;
import app.mnema.importer.client.core.CoreFieldTemplate;
import app.mnema.importer.client.core.CorePageResponse;
import app.mnema.importer.client.core.CorePublicDeckResponse;
import app.mnema.importer.client.core.CoreUserCardResponse;
import app.mnema.importer.client.core.CoreUserDeckResponse;
import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.repository.ImportJobRepository;
import app.mnema.importer.service.parser.MnemaPackageManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportProcessor {

    private static final String CSV_NAME = "deck.csv";
    private static final String MANIFEST_NAME = "deck.json";
    private static final String MEDIA_NAME = "media.json";
    private static final String MEDIA_DIR = "media/";
    private static final String MANIFEST_FORMAT = "mnema";
    private static final int MANIFEST_VERSION = 1;
    private static final String MNPKG_MANIFEST_TABLE = "manifest";
    private static final String MNPKG_CARDS_TABLE = "cards";
    private static final String MNPKG_MEDIA_TABLE = "media";
    private static final String MNPKG_FILE_NAME = "deck-export.mnpkg";

    private static final String ORDER_COLUMN = "__order";
    private static final String ANKI_FRONT_COLUMN = "__anki_front";
    private static final String ANKI_BACK_COLUMN = "__anki_back";
    private static final String ANKI_CSS_COLUMN = "__anki_css";
    private static final String ANKI_MODEL_ID_COLUMN = "__anki_modelId";
    private static final String ANKI_MODEL_NAME_COLUMN = "__anki_modelName";
    private static final String ANKI_TEMPLATE_NAME_COLUMN = "__anki_templateName";

    private static final Pattern MNEMA_MEDIA_PATTERN = Pattern.compile("mnema-media://([0-9a-fA-F-]{36})");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final int MEDIA_RESOLVE_BATCH = 200;

    private final CoreApiClient coreApiClient;
    private final MediaApiClient mediaApiClient;
    private final ImportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final int pageSize;

    public ExportProcessor(CoreApiClient coreApiClient,
                           MediaApiClient mediaApiClient,
                           ImportJobRepository jobRepository,
                           ObjectMapper objectMapper,
                           @Value("${app.import.export-page-size:200}") int pageSize) {
        this.coreApiClient = coreApiClient;
        this.mediaApiClient = mediaApiClient;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.pageSize = pageSize;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void process(ImportJobEntity job) {
        UUID userDeckId = job.getTargetDeckId();
        if (userDeckId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userDeckId is required for export");
        }
        ImportSourceType format = job.getSourceType() == null ? ImportSourceType.csv : job.getSourceType();
        if (format != ImportSourceType.csv && format != ImportSourceType.mnema && format != ImportSourceType.mnpkg) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported export format");
        }

        CoreUserDeckResponse userDeck = coreApiClient.getUserDeck(job.getUserAccessToken(), userDeckId);
        CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(job.getUserAccessToken(), userDeck.publicDeckId(), userDeck.currentVersion());
        CoreCardTemplateResponse template = coreApiClient.getTemplate(job.getUserAccessToken(), publicDeck.templateId());
        List<CoreFieldTemplate> fields = template.fields();

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("mnema-export-");
            ExportArtifact artifact;
            if (format == ImportSourceType.csv) {
                Path csvFile = tempDir.resolve(CSV_NAME);
                Path zipFile = tempDir.resolve("deck-export.zip");
                writeCsv(job, fields, csvFile, userDeckId);
                zipCsv(csvFile, zipFile);
                artifact = new ExportArtifact(zipFile, "application/zip", "deck-export.zip");
            } else if (format == ImportSourceType.mnema) {
                Path csvFile = tempDir.resolve(CSV_NAME);
                ExportScanResult scan = writePackageCsv(job, fields, csvFile, userDeckId);
                Path manifestFile = tempDir.resolve(MANIFEST_NAME);
                writeManifest(manifestFile, userDeck, publicDeck, template, scan.hasAnki());
                Map<UUID, MediaExportEntry> mediaEntries = resolveMediaEntries(scan.mediaIds());
                Path mediaFile = tempDir.resolve(MEDIA_NAME);
                writeMediaMap(mediaFile, mediaEntries);
                Path zipFile = tempDir.resolve("deck-export.zip");
                zipPackage(csvFile, manifestFile, mediaFile, mediaEntries, zipFile);
                artifact = new ExportArtifact(zipFile, "application/zip", "deck-export.zip");
            } else {
                Path sqliteFile = tempDir.resolve(MNPKG_FILE_NAME);
                writeMnpkg(job, fields, sqliteFile, userDeckId, userDeck, publicDeck, template);
                artifact = new ExportArtifact(sqliteFile, "application/vnd.mnema.package+sqlite", MNPKG_FILE_NAME);
            }

            try (InputStream inputStream = Files.newInputStream(artifact.path())) {
                UUID mediaId = mediaApiClient.directUpload(
                        job.getUserId(),
                        "import_file",
                        artifact.contentType(),
                        artifact.fileName(),
                        Files.size(artifact.path()),
                        inputStream
                );
                updateResult(job.getJobId(), mediaId);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export failed", ex);
        } finally {
            cleanup(tempDir);
        }
    }

    private void writeCsv(ImportJobEntity job,
                          List<CoreFieldTemplate> fields,
                          Path csvFile,
                          UUID userDeckId) throws IOException {
        List<String> headers = fields.stream().map(CoreFieldTemplate::name).toList();
        try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).build())) {
            int page = 1;
            int processed = 0;
            while (true) {
                CorePageResponse<CoreUserCardResponse> pageResult = coreApiClient.getUserCards(job.getUserAccessToken(), userDeckId, page, pageSize);
                if (pageResult == null || pageResult.content() == null || pageResult.content().isEmpty()) {
                    break;
                }
                for (CoreUserCardResponse card : pageResult.content()) {
                    List<String> row = new ArrayList<>();
                    for (CoreFieldTemplate field : fields) {
                        row.add(extractValue(card.effectiveContent(), field.name()));
                    }
                    printer.printRecord(row);
                    processed++;
                }
                updateProgress(job.getJobId(), processed);
                if (pageResult.last()) {
                    break;
                }
                page++;
            }
            updateTotals(job.getJobId(), processed);
        }
    }

    private String extractValue(JsonNode content, String fieldName) {
        if (content == null || !content.has(fieldName)) {
            return "";
        }
        JsonNode value = content.get(fieldName);
        if (value.isObject()) {
            JsonNode mediaId = value.get("mediaId");
            return mediaId == null ? "" : mediaId.asText();
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }

    private void writeMnpkg(ImportJobEntity job,
                            List<CoreFieldTemplate> fields,
                            Path sqliteFile,
                            UUID userDeckId,
                            CoreUserDeckResponse userDeck,
                            CorePublicDeckResponse publicDeck,
                            CoreCardTemplateResponse template) throws IOException {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
            initializeMnpkgSchema(connection);

            MnpkgCardsResult cardsResult = writeMnpkgCards(connection, job, fields, userDeckId);
            Map<UUID, MediaExportEntry> mediaEntries = resolveMediaEntries(cardsResult.mediaIds());
            writeMnpkgMedia(connection, mediaEntries);

            MnemaPackageManifest manifest = buildManifest(userDeck, publicDeck, template, cardsResult.hasAnki());
            writeMnpkgManifest(connection, manifest);
        } catch (SQLException ex) {
            throw new IOException("Failed to write mnpkg sqlite package", ex);
        } finally {
            closeQuietly(connection);
        }
    }

    private void initializeMnpkgSchema(Connection connection) throws SQLException {
        try (PreparedStatement manifestTable = connection.prepareStatement(
                     "create table if not exists " + MNPKG_MANIFEST_TABLE + " (" +
                             "manifest_json text not null" +
                             ")"
             );
             PreparedStatement cardsTable = connection.prepareStatement(
                     "create table if not exists " + MNPKG_CARDS_TABLE + " (" +
                             "row_index integer primary key autoincrement," +
                             "order_index integer," +
                             "fields_json text not null," +
                             "anki_json text" +
                             ")"
             );
             PreparedStatement mediaTable = connection.prepareStatement(
                     "create table if not exists " + MNPKG_MEDIA_TABLE + " (" +
                             "media_id text primary key," +
                             "file_name text not null," +
                             "kind text," +
                             "mime_type text," +
                             "size_bytes integer," +
                             "payload blob not null" +
                             ")"
             )) {
            manifestTable.execute();
            cardsTable.execute();
            mediaTable.execute();
        }
    }

    private MnpkgCardsResult writeMnpkgCards(Connection connection,
                                             ImportJobEntity job,
                                             List<CoreFieldTemplate> fields,
                                             UUID userDeckId) throws SQLException, IOException {
        Set<UUID> mediaIds = new HashSet<>();
        boolean hasAnki = false;
        int orderIndex = 0;
        int processed = 0;

        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + MNPKG_CARDS_TABLE + " (order_index, fields_json, anki_json) values (?, ?, ?)"
        )) {
            int page = 1;
            while (true) {
                CorePageResponse<CoreUserCardResponse> pageResult = coreApiClient.getUserCards(job.getUserAccessToken(), userDeckId, page, pageSize);
                if (pageResult == null || pageResult.content() == null || pageResult.content().isEmpty()) {
                    break;
                }

                for (CoreUserCardResponse card : pageResult.content()) {
                    JsonNode content = card == null ? null : card.effectiveContent();
                    ObjectNode fieldsNode = objectMapper.createObjectNode();
                    for (CoreFieldTemplate field : fields) {
                        String value = extractValue(content, field.name());
                        fieldsNode.put(field.name(), value);
                        collectMediaIds(content == null ? null : content.get(field.name()), mediaIds);
                        if (value != null && !value.isBlank()) {
                            collectMediaIdsFromText(value, mediaIds);
                        }
                    }

                    ObjectNode ankiNode = null;
                    AnkiColumns ankiColumns = extractAnkiColumns(content);
                    if (ankiColumns != null) {
                        hasAnki = true;
                        ankiNode = objectMapper.createObjectNode();
                        ankiNode.put("front", ankiColumns.front());
                        ankiNode.put("back", ankiColumns.back());
                        ankiNode.put("css", ankiColumns.css());
                        ankiNode.put("modelId", ankiColumns.modelId());
                        ankiNode.put("modelName", ankiColumns.modelName());
                        ankiNode.put("templateName", ankiColumns.templateName());

                        collectMediaIdsFromText(ankiColumns.front(), mediaIds);
                        collectMediaIdsFromText(ankiColumns.back(), mediaIds);
                        collectMediaIdsFromText(ankiColumns.css(), mediaIds);
                    }

                    statement.setInt(1, orderIndex);
                    statement.setString(2, objectMapper.writeValueAsString(fieldsNode));
                    if (ankiNode == null) {
                        statement.setNull(3, java.sql.Types.VARCHAR);
                    } else {
                        statement.setString(3, objectMapper.writeValueAsString(ankiNode));
                    }
                    statement.executeUpdate();

                    orderIndex++;
                    processed++;
                }

                updateProgress(job.getJobId(), processed);
                if (pageResult.last()) {
                    break;
                }
                page++;
            }
        }

        updateTotals(job.getJobId(), processed);
        return new MnpkgCardsResult(mediaIds, hasAnki);
    }

    private void writeMnpkgMedia(Connection connection, Map<UUID, MediaExportEntry> mediaEntries) throws SQLException {
        if (mediaEntries == null || mediaEntries.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "insert or replace into " + MNPKG_MEDIA_TABLE + " " +
                        "(media_id, file_name, kind, mime_type, size_bytes, payload) values (?, ?, ?, ?, ?, ?)"
        )) {
            for (MediaExportEntry entry : mediaEntries.values()) {
                if (entry == null || entry.url() == null || entry.url().isBlank()) {
                    continue;
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(entry.url()))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();
                try {
                    HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        continue;
                    }
                    try (InputStream body = response.body()) {
                        byte[] payload = body.readAllBytes();
                        statement.setString(1, entry.mediaId().toString());
                        statement.setString(2, entry.fileName());
                        statement.setString(3, entry.kind());
                        statement.setString(4, entry.mimeType());
                        if (entry.sizeBytes() == null) {
                            statement.setNull(5, java.sql.Types.BIGINT);
                        } else {
                            statement.setLong(5, entry.sizeBytes());
                        }
                        statement.setBytes(6, payload);
                        statement.executeUpdate();
                    }
                } catch (IOException | InterruptedException ignored) {
                    if (ignored instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void writeMnpkgManifest(Connection connection, MnemaPackageManifest manifest) throws SQLException, IOException {
        try (PreparedStatement clear = connection.prepareStatement("delete from " + MNPKG_MANIFEST_TABLE);
             PreparedStatement insert = connection.prepareStatement(
                     "insert into " + MNPKG_MANIFEST_TABLE + " (manifest_json) values (?)"
             )) {
            clear.executeUpdate();
            insert.setString(1, objectMapper.writeValueAsString(manifest));
            insert.executeUpdate();
        }
    }

    private ExportScanResult writePackageCsv(ImportJobEntity job,
                                             List<CoreFieldTemplate> fields,
                                             Path csvFile,
                                             UUID userDeckId) throws IOException {
        List<String> headers = new ArrayList<>();
        headers.addAll(fields.stream().map(CoreFieldTemplate::name).toList());
        headers.add(ORDER_COLUMN);
        headers.add(ANKI_FRONT_COLUMN);
        headers.add(ANKI_BACK_COLUMN);
        headers.add(ANKI_CSS_COLUMN);
        headers.add(ANKI_MODEL_ID_COLUMN);
        headers.add(ANKI_MODEL_NAME_COLUMN);
        headers.add(ANKI_TEMPLATE_NAME_COLUMN);

        Set<UUID> mediaIds = new HashSet<>();
        boolean hasAnki = false;
        int orderIndex = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).build())) {
            int page = 1;
            int processed = 0;
            while (true) {
                CorePageResponse<CoreUserCardResponse> pageResult = coreApiClient.getUserCards(job.getUserAccessToken(), userDeckId, page, pageSize);
                if (pageResult == null || pageResult.content() == null || pageResult.content().isEmpty()) {
                    break;
                }
                for (CoreUserCardResponse card : pageResult.content()) {
                    JsonNode content = card == null ? null : card.effectiveContent();
                    List<String> row = new ArrayList<>(headers.size());
                    for (CoreFieldTemplate field : fields) {
                        String value = extractValue(content, field.name());
                        row.add(value);
                        collectMediaIds(content == null ? null : content.get(field.name()), mediaIds);
                        if (value != null && !value.isBlank()) {
                            collectMediaIdsFromText(value, mediaIds);
                        }
                    }
                    row.add(Integer.toString(orderIndex));
                    orderIndex++;

                    AnkiColumns ankiColumns = extractAnkiColumns(content);
                    if (ankiColumns != null) {
                        hasAnki = true;
                        row.add(ankiColumns.front());
                        row.add(ankiColumns.back());
                        row.add(ankiColumns.css());
                        row.add(ankiColumns.modelId());
                        row.add(ankiColumns.modelName());
                        row.add(ankiColumns.templateName());
                        collectMediaIdsFromText(ankiColumns.front(), mediaIds);
                        collectMediaIdsFromText(ankiColumns.back(), mediaIds);
                        collectMediaIdsFromText(ankiColumns.css(), mediaIds);
                    } else {
                        row.add("");
                        row.add("");
                        row.add("");
                        row.add("");
                        row.add("");
                        row.add("");
                    }
                    printer.printRecord(row);
                    processed++;
                }
                updateProgress(job.getJobId(), processed);
                if (pageResult.last()) {
                    break;
                }
                page++;
            }
            updateTotals(job.getJobId(), processed);
        }

        return new ExportScanResult(mediaIds, hasAnki);
    }

    private AnkiColumns extractAnkiColumns(JsonNode content) {
        if (content == null) {
            return null;
        }
        JsonNode anki = content.get("_anki");
        if (anki == null || !anki.isObject()) {
            return null;
        }
        return new AnkiColumns(
                textValue(anki.get("front")),
                textValue(anki.get("back")),
                textValue(anki.get("css")),
                textValue(anki.get("modelId")),
                textValue(anki.get("modelName")),
                textValue(anki.get("templateName"))
        );
    }

    private String textValue(JsonNode node) {
        return node == null ? "" : node.asText("");
    }

    private void collectMediaIds(JsonNode node, Set<UUID> mediaIds) {
        if (node == null || mediaIds == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode mediaId = node.get("mediaId");
            if (mediaId != null && mediaId.isTextual()) {
                UUID parsed = parseUuid(mediaId.asText());
                if (parsed != null) {
                    mediaIds.add(parsed);
                }
            }
            return;
        }
        if (node.isTextual()) {
            collectMediaIdsFromText(node.asText(), mediaIds);
        }
    }

    private void collectMediaIdsFromText(String value, Set<UUID> mediaIds) {
        if (value == null || value.isBlank() || mediaIds == null) {
            return;
        }
        Matcher matcher = MNEMA_MEDIA_PATTERN.matcher(value);
        while (matcher.find()) {
            UUID parsed = parseUuid(matcher.group(1));
            if (parsed != null) {
                mediaIds.add(parsed);
            }
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!UUID_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void writeManifest(Path manifestFile,
                               CoreUserDeckResponse userDeck,
                               CorePublicDeckResponse publicDeck,
                               CoreCardTemplateResponse template,
                               boolean hasAnki) throws IOException {
        MnemaPackageManifest manifest = buildManifest(userDeck, publicDeck, template, hasAnki);
        objectMapper.writeValue(manifestFile.toFile(), manifest);
    }

    private MnemaPackageManifest buildManifest(CoreUserDeckResponse userDeck,
                                               CorePublicDeckResponse publicDeck,
                                               CoreCardTemplateResponse template,
                                               boolean hasAnki) {
        String deckName = firstNonBlank(
                userDeck == null ? null : userDeck.displayName(),
                publicDeck == null ? null : publicDeck.name(),
                template == null ? null : template.name(),
                "Mnema deck"
        );
        String deckDescription = firstNonBlank(
                userDeck == null ? null : userDeck.displayDescription(),
                publicDeck == null ? null : publicDeck.description()
        );
        String language = publicDeck == null ? null : publicDeck.language();
        String[] tags = publicDeck == null || publicDeck.tags() == null ? new String[0] : publicDeck.tags();

        List<MnemaPackageManifest.FieldMeta> fieldMeta = new ArrayList<>();
        if (template != null && template.fields() != null) {
            for (CoreFieldTemplate field : template.fields()) {
                fieldMeta.add(new MnemaPackageManifest.FieldMeta(
                        field.name(),
                        field.label(),
                        field.fieldType(),
                        field.isRequired(),
                        field.isOnFront(),
                        field.orderIndex(),
                        field.defaultValue(),
                        field.helpText()
                ));
            }
        }

        boolean anki = hasAnki || isTemplateAnki(template);
        MnemaPackageManifest manifest = new MnemaPackageManifest(
                MANIFEST_FORMAT,
                MANIFEST_VERSION,
                new MnemaPackageManifest.DeckMeta(
                        deckName,
                        deckDescription,
                        language,
                        tags
                ),
                new MnemaPackageManifest.TemplateMeta(
                        template == null ? null : template.name(),
                        template == null ? null : template.description(),
                        template == null ? null : template.layout(),
                        anki,
                        fieldMeta
                )
        );
        return manifest;
    }

    private boolean isTemplateAnki(CoreCardTemplateResponse template) {
        if (template == null || template.layout() == null) {
            return false;
        }
        JsonNode renderMode = template.layout().path("renderMode");
        return renderMode.isTextual() && "anki".equalsIgnoreCase(renderMode.asText());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<UUID, MediaExportEntry> resolveMediaEntries(Set<UUID> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = new ArrayList<>(mediaIds);
        Map<UUID, MediaExportEntry> entries = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += MEDIA_RESOLVE_BATCH) {
            List<UUID> batch = ids.subList(i, Math.min(ids.size(), i + MEDIA_RESOLVE_BATCH));
            Map<UUID, app.mnema.importer.client.media.MediaResolved> resolved = mediaApiClient.resolveMap(batch);
            for (UUID id : batch) {
                app.mnema.importer.client.media.MediaResolved media = resolved.get(id);
                if (media == null || media.url() == null || media.url().isBlank()) {
                    continue;
                }
                String fileName = buildFileName(id, media.mimeType(), media.url());
                entries.put(id, new MediaExportEntry(
                        id,
                        media.kind(),
                        media.mimeType(),
                        media.url(),
                        fileName,
                        media.sizeBytes()
                ));
            }
        }
        return entries;
    }

    private String buildFileName(UUID mediaId, String mimeType, String url) {
        String extension = extensionFromMime(mimeType, url);
        if (extension == null || extension.isBlank()) {
            return mediaId.toString();
        }
        return mediaId + "." + extension;
    }

    private String extensionFromMime(String mimeType, String url) {
        if (mimeType != null && !mimeType.isBlank()) {
            String normalized = mimeType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            return switch (normalized) {
                case "image/png" -> "png";
                case "image/jpeg", "image/jpg" -> "jpg";
                case "image/gif" -> "gif";
                case "image/webp" -> "webp";
                case "image/svg+xml" -> "svg";
                case "image/bmp" -> "bmp";
                case "audio/mpeg", "audio/mp3" -> "mp3";
                case "audio/mp4" -> "m4a";
                case "audio/aac" -> "aac";
                case "audio/ogg", "audio/opus" -> "ogg";
                case "audio/wav", "audio/wave", "audio/x-wav" -> "wav";
                case "audio/webm", "video/webm" -> "webm";
                case "video/mp4" -> "mp4";
                case "video/ogg" -> "ogv";
                default -> extensionFromUrl(url);
            };
        }
        return extensionFromUrl(url);
    }

    private String extensionFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url;
        int query = trimmed.indexOf('?');
        if (query >= 0) {
            trimmed = trimmed.substring(0, query);
        }
        int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            trimmed = trimmed.substring(0, hash);
        }
        int slash = trimmed.lastIndexOf('/');
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void writeMediaMap(Path mediaFile, Map<UUID, MediaExportEntry> mediaEntries) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        if (mediaEntries != null) {
            for (MediaExportEntry entry : mediaEntries.values()) {
                if (entry == null) {
                    continue;
                }
                ObjectNode meta = root.putObject(entry.mediaId().toString());
                meta.put("fileName", entry.fileName());
                if (entry.kind() != null) {
                    meta.put("kind", entry.kind());
                }
                if (entry.mimeType() != null) {
                    meta.put("mimeType", entry.mimeType());
                }
                if (entry.sizeBytes() != null) {
                    meta.put("sizeBytes", entry.sizeBytes());
                }
            }
        }
        objectMapper.writeValue(mediaFile.toFile(), root);
    }

    private void zipPackage(Path csvFile,
                            Path manifestFile,
                            Path mediaFile,
                            Map<UUID, MediaExportEntry> mediaEntries,
                            Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            addFile(zos, csvFile, CSV_NAME);
            addFile(zos, manifestFile, MANIFEST_NAME);
            addFile(zos, mediaFile, MEDIA_NAME);

            if (mediaEntries != null) {
                for (MediaExportEntry entry : mediaEntries.values()) {
                    if (entry == null || entry.url() == null || entry.url().isBlank()) {
                        continue;
                    }
                    try {
                        downloadMedia(zos, entry);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void addFile(ZipOutputStream zos, Path path, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        Files.copy(path, zos);
        zos.closeEntry();
    }

    private void downloadMedia(ZipOutputStream zos, MediaExportEntry entry) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(entry.url()))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return;
        }
        ZipEntry zipEntry = new ZipEntry(MEDIA_DIR + entry.fileName());
        zos.putNextEntry(zipEntry);
        try (InputStream inputStream = response.body()) {
            inputStream.transferTo(zos);
        }
        zos.closeEntry();
    }

    private void zipCsv(Path csvFile, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry("deck.csv");
            zos.putNextEntry(entry);
            Files.copy(csvFile, zos);
            zos.closeEntry();
        }
    }

    @Transactional
    protected void updateResult(UUID jobId, UUID mediaId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setResultMediaId(mediaId);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    protected void updateProgress(UUID jobId, int processed) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedItems(processed);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    protected void updateTotals(UUID jobId, int processed) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedItems(processed);
            job.setTotalItems(processed);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    private void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private void cleanup(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try (var stream = Files.list(tempDir)) {
            stream.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(tempDir);
        } catch (IOException ignored) {
        }
    }

    private record ExportScanResult(Set<UUID> mediaIds, boolean hasAnki) {
    }

    private record MnpkgCardsResult(Set<UUID> mediaIds, boolean hasAnki) {
    }

    private record ExportArtifact(Path path, String contentType, String fileName) {
    }

    private record AnkiColumns(String front,
                               String back,
                               String css,
                               String modelId,
                               String modelName,
                               String templateName) {
    }

    private record MediaExportEntry(UUID mediaId,
                                    String kind,
                                    String mimeType,
                                    String url,
                                    String fileName,
                                    Long sizeBytes) {
    }
}
