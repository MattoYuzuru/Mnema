package app.mnema.importer.service.parser;

import app.mnema.importer.client.core.CoreFieldTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MnpkgImportParser implements ImportParser {

    private static final String MANIFEST_TABLE = "manifest";
    private static final String CARDS_TABLE = "cards";
    private static final String MEDIA_TABLE = "media";

    private final ObjectMapper objectMapper;

    public MnpkgImportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportPreview preview(InputStream inputStream, int sampleSize) throws IOException {
        try (MnpkgImportStream stream = openStream(inputStream)) {
            List<ImportRecord> sample = new ArrayList<>();
            while (stream.hasNext() && sample.size() < sampleSize) {
                ImportRecord record = stream.next();
                if (record != null) {
                    sample.add(record);
                }
            }
            return new ImportPreview(stream.fields(), sample);
        }
    }

    @Override
    public MnpkgImportStream openStream(InputStream inputStream) throws IOException {
        Path tempDir = Files.createTempDirectory("mnema-mnpkg-");
        Path sqliteFile = tempDir.resolve("package.mnpkg");
        Files.copy(inputStream, sqliteFile, StandardCopyOption.REPLACE_EXISTING);

        Connection connection;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
        } catch (SQLException ex) {
            throw new IOException("Failed to open mnpkg sqlite", ex);
        }

        try {
            if (!tableExists(connection, CARDS_TABLE)) {
                throw new IOException("Invalid mnpkg: cards table is missing");
            }

            MnemaPackageManifest manifest = readManifest(connection);
            TemplateMetadata template = TemplateMetadata.from(manifest);
            List<CoreFieldTemplate> templateFields = template == null ? List.of() : template.fields();
            List<String> fields = resolveFields(connection, templateFields);
            if (fields.isEmpty()) {
                throw new IOException("Invalid mnpkg: no fields found");
            }

            boolean anki = (template != null && template.anki()) || hasAnkiCards(connection);
            ImportLayout layout = template == null ? null : template.layout();
            boolean hasMediaTable = tableExists(connection, MEDIA_TABLE);

            PreparedStatement statement = connection.prepareStatement(
                    "select row_index, order_index, fields_json, anki_json from cards order by row_index"
            );
            ResultSet resultSet = statement.executeQuery();

            return new MnpkgImportStream(
                    tempDir,
                    sqliteFile,
                    connection,
                    statement,
                    resultSet,
                    fields,
                    layout,
                    anki,
                    templateFields,
                    hasMediaTable
            );
        } catch (Exception ex) {
            closeQuietly(connection);
            cleanup(tempDir, sqliteFile);
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to open mnpkg package", ex);
        }
    }

    private boolean tableExists(Connection connection, String tableName) {
        if (connection == null || tableName == null || tableName.isBlank()) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from sqlite_master where type='table' and name=? limit 1"
        )) {
            statement.setString(1, tableName);
            ResultSet resultSet = statement.executeQuery();
            return resultSet.next();
        } catch (SQLException ex) {
            return false;
        }
    }

    private MnemaPackageManifest readManifest(Connection connection) {
        if (!tableExists(connection, MANIFEST_TABLE)) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "select manifest_json from manifest limit 1"
        )) {
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                return null;
            }
            String payload = resultSet.getString("manifest_json");
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return objectMapper.readValue(payload, MnemaPackageManifest.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> resolveFields(Connection connection, List<CoreFieldTemplate> templateFields) {
        if (templateFields != null && !templateFields.isEmpty()) {
            return templateFields.stream()
                    .sorted(Comparator.comparingInt(field -> field.orderIndex() == null ? Integer.MAX_VALUE : field.orderIndex()))
                    .map(CoreFieldTemplate::name)
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "select fields_json from cards order by row_index limit 1"
        )) {
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                return List.of();
            }
            String payload = resultSet.getString("fields_json");
            if (payload == null || payload.isBlank()) {
                return List.of();
            }
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean hasAnkiCards(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from cards where anki_json is not null and trim(anki_json) <> '' limit 1"
        )) {
            ResultSet resultSet = statement.executeQuery();
            return resultSet.next();
        } catch (SQLException ex) {
            return false;
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

    private void cleanup(Path tempDir, Path sqliteFile) {
        if (sqliteFile != null) {
            try {
                Files.deleteIfExists(sqliteFile);
            } catch (IOException ignored) {
            }
        }
        if (tempDir == null) {
            return;
        }
        try {
            try (var stream = Files.list(tempDir)) {
                stream.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
            Files.deleteIfExists(tempDir);
        } catch (IOException ignored) {
        }
    }

    public class MnpkgImportStream implements MediaImportStream, TemplateAwareImportStream {

        private final Path tempDir;
        private final Path sqliteFile;
        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet resultSet;
        private final List<String> fields;
        private final ImportLayout layout;
        private final boolean anki;
        private final List<CoreFieldTemplate> templateFields;
        private final boolean hasMediaTable;

        private boolean hasNext;
        private boolean finished;

        MnpkgImportStream(Path tempDir,
                          Path sqliteFile,
                          Connection connection,
                          PreparedStatement statement,
                          ResultSet resultSet,
                          List<String> fields,
                          ImportLayout layout,
                          boolean anki,
                          List<CoreFieldTemplate> templateFields,
                          boolean hasMediaTable) throws IOException {
            this.tempDir = tempDir;
            this.sqliteFile = sqliteFile;
            this.connection = connection;
            this.statement = statement;
            this.resultSet = resultSet;
            this.fields = List.copyOf(fields);
            this.layout = layout;
            this.anki = anki;
            this.templateFields = templateFields == null ? List.of() : List.copyOf(templateFields);
            this.hasMediaTable = hasMediaTable;
            advance();
        }

        @Override
        public List<String> fields() {
            return fields;
        }

        @Override
        public ImportLayout layout() {
            return layout;
        }

        @Override
        public boolean isAnki() {
            return anki;
        }

        @Override
        public List<CoreFieldTemplate> templateFields() {
            return templateFields;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public ImportRecord next() {
            if (!hasNext) {
                return null;
            }
            try {
                String fieldsJson = resultSet.getString("fields_json");
                Map<String, String> values = parseFields(fieldsJson);
                Integer orderIndex = parseOrder(resultSet);
                ImportAnkiTemplate ankiTemplate = parseAnkiTemplate(resultSet.getString("anki_json"));
                advance();
                return new ImportRecord(values, null, ankiTemplate, orderIndex);
            } catch (Exception ex) {
                finished = true;
                hasNext = false;
                return null;
            }
        }

        @Override
        public ImportMedia openMedia(String mediaName) throws IOException {
            if (!hasMediaTable || mediaName == null || mediaName.isBlank()) {
                return null;
            }
            String normalized = mediaName.trim();
            if (normalized.startsWith("mnema-media://")) {
                normalized = normalized.substring("mnema-media://".length());
            }
            byte[] payload = loadMediaById(normalized);
            if (payload == null) {
                payload = loadMediaByName(normalized);
            }
            if (payload == null || payload.length == 0) {
                return null;
            }
            return new ImportMedia(new ByteArrayInputStream(payload), payload.length);
        }

        @Override
        public void close() throws IOException {
            closeQuietly(resultSet);
            closeQuietly(statement);
            closeQuietly(connection);
            cleanup(tempDir, sqliteFile);
        }

        private Map<String, String> parseFields(String fieldsJson) throws IOException {
            Map<String, String> values = new LinkedHashMap<>();
            if (fieldsJson == null || fieldsJson.isBlank()) {
                for (String field : fields) {
                    values.put(field, "");
                }
                return values;
            }
            JsonNode node = objectMapper.readTree(fieldsJson);
            for (String field : fields) {
                String value = "";
                if (node != null && node.isObject()) {
                    JsonNode fieldNode = node.get(field);
                    if (fieldNode != null && !fieldNode.isNull()) {
                        value = fieldNode.asText("");
                    }
                }
                values.put(field, value);
            }
            return values;
        }

        private Integer parseOrder(ResultSet rs) {
            try {
                int value = rs.getInt("order_index");
                return rs.wasNull() ? null : value;
            } catch (SQLException ex) {
                return null;
            }
        }

        private ImportAnkiTemplate parseAnkiTemplate(String ankiJson) {
            if (ankiJson == null || ankiJson.isBlank()) {
                return null;
            }
            try {
                JsonNode node = objectMapper.readTree(ankiJson);
                if (node == null || !node.isObject()) {
                    return null;
                }
                String front = node.path("front").asText("");
                String back = node.path("back").asText("");
                if (front.isBlank() && back.isBlank()) {
                    return null;
                }
                String css = node.path("css").asText("");
                String modelId = emptyToNull(node.path("modelId").asText(null));
                String modelName = emptyToNull(node.path("modelName").asText(null));
                String templateName = emptyToNull(node.path("templateName").asText(null));
                return new ImportAnkiTemplate(modelId, modelName, templateName, front, back, css);
            } catch (IOException ex) {
                return null;
            }
        }

        private String emptyToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private byte[] loadMediaById(String mediaId) {
            try (PreparedStatement mediaStatement = connection.prepareStatement(
                    "select payload from media where media_id = ? limit 1"
            )) {
                mediaStatement.setString(1, mediaId);
                ResultSet mediaResult = mediaStatement.executeQuery();
                if (!mediaResult.next()) {
                    return null;
                }
                return mediaResult.getBytes("payload");
            } catch (SQLException ex) {
                return null;
            }
        }

        private byte[] loadMediaByName(String fileName) {
            try (PreparedStatement mediaStatement = connection.prepareStatement(
                    "select payload from media where file_name = ? limit 1"
            )) {
                mediaStatement.setString(1, fileName);
                ResultSet mediaResult = mediaStatement.executeQuery();
                if (!mediaResult.next()) {
                    return null;
                }
                return mediaResult.getBytes("payload");
            } catch (SQLException ex) {
                return null;
            }
        }

        private void advance() {
            if (finished) {
                hasNext = false;
                return;
            }
            try {
                hasNext = resultSet.next();
                if (!hasNext) {
                    finished = true;
                }
            } catch (SQLException ex) {
                hasNext = false;
                finished = true;
            }
        }
    }

    private record TemplateMetadata(List<CoreFieldTemplate> fields, ImportLayout layout, boolean anki) {
        static TemplateMetadata from(MnemaPackageManifest manifest) {
            if (manifest == null || manifest.template() == null) {
                return null;
            }
            MnemaPackageManifest.TemplateMeta template = manifest.template();
            List<CoreFieldTemplate> fields = new ArrayList<>();
            if (template.fields() != null) {
                for (MnemaPackageManifest.FieldMeta field : template.fields()) {
                    if (field == null || field.name() == null || field.name().isBlank()) {
                        continue;
                    }
                    fields.add(new CoreFieldTemplate(
                            null,
                            null,
                            field.name(),
                            field.label() == null ? field.name() : field.label(),
                            field.fieldType(),
                            field.isRequired(),
                            field.isOnFront(),
                            field.orderIndex(),
                            field.defaultValue(),
                            field.helpText()
                    ));
                }
            }

            ImportLayout layout = null;
            if (template.layout() != null && template.layout().isObject()) {
                JsonNode frontNode = template.layout().path("front");
                JsonNode backNode = template.layout().path("back");
                List<String> front = new ArrayList<>();
                List<String> back = new ArrayList<>();
                if (frontNode.isArray()) {
                    frontNode.forEach(n -> front.add(n.asText()));
                }
                if (backNode.isArray()) {
                    backNode.forEach(n -> back.add(n.asText()));
                }
                layout = new ImportLayout(front, back);
            } else if (!fields.isEmpty()) {
                List<String> front = new ArrayList<>();
                List<String> back = new ArrayList<>();
                fields.stream()
                        .sorted(Comparator.comparingInt(field -> field.orderIndex() == null ? Integer.MAX_VALUE : field.orderIndex()))
                        .forEach(field -> {
                            if (field.isOnFront()) {
                                front.add(field.name());
                            } else {
                                back.add(field.name());
                            }
                        });
                layout = new ImportLayout(front, back);
            }
            return new TemplateMetadata(fields, layout, template.anki());
        }
    }
}
