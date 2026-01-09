package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkgImportParser implements ImportParser {

    private static final String COLLECTION_NAME = "collection.anki2";
    private static final String MEDIA_NAME = "media";

    private final ObjectMapper objectMapper;

    public ApkgImportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportPreview preview(InputStream inputStream, int sampleSize) throws IOException {
        try (ApkgImportStream stream = openStream(inputStream)) {
            List<ImportRecord> sample = new ArrayList<>();
            while (stream.hasNext() && sample.size() < sampleSize) {
                sample.add(stream.next());
            }
            return new ImportPreview(stream.fields(), sample);
        }
    }

    @Override
    public ApkgImportStream openStream(InputStream inputStream) throws IOException {
        Path tempDir = Files.createTempDirectory("mnema-apkg-");
        Path apkgFile = tempDir.resolve("deck.apkg");
        Files.copy(inputStream, apkgFile, StandardCopyOption.REPLACE_EXISTING);

        ZipFile zipFile = new ZipFile(apkgFile.toFile());
        Path collectionFile = extractCollection(zipFile, tempDir);
        Map<String, String> mediaIndexToName = readMediaMap(zipFile);
        Map<String, String> mediaNameToIndex = invert(mediaIndexToName);

        Connection connection;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + collectionFile.toAbsolutePath());
        } catch (SQLException ex) {
            closeQuietly(zipFile);
            throw new IOException("Failed to open apkg sqlite", ex);
        }

        Map<String, List<String>> modelFields = loadModelFields(connection);
        List<String> unionFields = unionFields(modelFields);

        try {
            PreparedStatement stmt = connection.prepareStatement("select id, mid, flds from notes");
            ResultSet rs = stmt.executeQuery();
            return new ApkgImportStream(
                    tempDir,
                    zipFile,
                    connection,
                    stmt,
                    rs,
                    unionFields,
                    modelFields,
                    mediaNameToIndex
            );
        } catch (SQLException ex) {
            closeQuietly(connection);
            closeQuietly(zipFile);
            throw new IOException("Failed to query notes", ex);
        }
    }

    private Path extractCollection(ZipFile zipFile, Path tempDir) throws IOException {
        ZipEntry entry = zipFile.getEntry(COLLECTION_NAME);
        if (entry == null) {
            throw new IOException("Missing collection.anki2 in apkg");
        }
        Path collectionFile = tempDir.resolve(COLLECTION_NAME);
        try (InputStream in = zipFile.getInputStream(entry)) {
            Files.copy(in, collectionFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return collectionFile;
    }

    private Map<String, String> readMediaMap(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry(MEDIA_NAME);
        if (entry == null) {
            return Map.of();
        }
        try (InputStream in = zipFile.getInputStream(entry)) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return Map.of();
            }
            JsonNode node = objectMapper.readTree(json);
            Map<String, String> map = new HashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
            return map;
        }
    }

    private Map<String, List<String>> loadModelFields(Connection connection) throws IOException {
        try (PreparedStatement stmt = connection.prepareStatement("select models from col limit 1")) {
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) {
                return Map.of();
            }
            String json = rs.getString(1);
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            JsonNode node = objectMapper.readTree(json);
            Map<String, List<String>> modelFields = new HashMap<>();
            node.fields().forEachRemaining(entry -> {
                JsonNode fieldsNode = entry.getValue().path("flds");
                List<String> fields = new ArrayList<>();
                for (JsonNode f : fieldsNode) {
                    String name = f.path("name").asText();
                    if (!name.isBlank()) {
                        fields.add(name);
                    }
                }
                modelFields.put(entry.getKey(), fields);
            });
            return modelFields;
        } catch (SQLException ex) {
            throw new IOException("Failed to read models", ex);
        }
    }

    private List<String> unionFields(Map<String, List<String>> modelFields) {
        Set<String> union = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        for (List<String> fields : modelFields.values()) {
            for (String field : fields) {
                if (union.add(field)) {
                    ordered.add(field);
                }
            }
        }
        return ordered;
    }

    private Map<String, String> invert(Map<String, String> indexToName) {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, String> entry : indexToName.entrySet()) {
            map.put(entry.getValue(), entry.getKey());
        }
        return map;
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

    public static class ApkgImportStream implements ImportStream {
        private static final String FIELD_SEPARATOR = "\u001f";

        private final Path tempDir;
        private final ZipFile zipFile;
        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet resultSet;
        private final List<String> fields;
        private final Map<String, List<String>> modelFields;
        private final Map<String, String> mediaNameToIndex;

        private boolean hasNext;
        private boolean finished;

        ApkgImportStream(Path tempDir,
                         ZipFile zipFile,
                         Connection connection,
                         PreparedStatement statement,
                         ResultSet resultSet,
                         List<String> fields,
                         Map<String, List<String>> modelFields,
                         Map<String, String> mediaNameToIndex) throws IOException {
            this.tempDir = tempDir;
            this.zipFile = zipFile;
            this.connection = connection;
            this.statement = statement;
            this.resultSet = resultSet;
            this.fields = List.copyOf(fields);
            this.modelFields = modelFields;
            this.mediaNameToIndex = mediaNameToIndex;
            advance();
        }

        @Override
        public List<String> fields() {
            return fields;
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
                long mid = resultSet.getLong("mid");
                String flds = resultSet.getString("flds");
                Map<String, String> values = mapFields(String.valueOf(mid), flds);
                advance();
                return new ImportRecord(values);
            } catch (SQLException ex) {
                finished = true;
                hasNext = false;
                return null;
            }
        }

        public InputStream openMediaStream(String mediaName) throws IOException {
            String index = mediaNameToIndex.get(mediaName);
            if (index == null) {
                return null;
            }
            ZipEntry entry = zipFile.getEntry(index);
            if (entry == null) {
                return null;
            }
            return zipFile.getInputStream(entry);
        }

        @Override
        public void close() throws IOException {
            try {
                resultSet.close();
            } catch (SQLException ignored) {
            }
            try {
                statement.close();
            } catch (SQLException ignored) {
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            try {
                zipFile.close();
            } catch (IOException ignored) {
            }
            if (tempDir != null) {
                try (var stream = Files.list(tempDir)) {
                    stream.forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                }
                Files.deleteIfExists(tempDir);
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

        private Map<String, String> mapFields(String modelId, String raw) {
            String[] values = raw == null ? new String[0] : raw.split(FIELD_SEPARATOR, -1);
            List<String> modelFieldNames = modelFields.getOrDefault(modelId, fields);
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < modelFieldNames.size(); i++) {
                String name = modelFieldNames.get(i);
                String value = i < values.length ? values[i] : "";
                map.put(name, value);
            }
            for (String field : fields) {
                map.putIfAbsent(field, "");
            }
            return map;
        }
    }
}
