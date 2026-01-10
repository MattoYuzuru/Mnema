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
        int totalItems = countNotes(connection);
        Map<Long, ImportRecordProgress> noteProgress = loadNoteProgress(connection);

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
                    totalItems,
                    modelFields,
                    noteProgress,
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

    private int countNotes(Connection connection) {
        try (PreparedStatement stmt = connection.prepareStatement("select count(*) from notes")) {
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            return 0;
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

    private Map<Long, ImportRecordProgress> loadNoteProgress(Connection connection) {
        Map<Long, ImportRecordProgress> progress = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "select nid, ivl, factor, reps, queue, type from cards")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                long noteId = rs.getLong("nid");
                ImportRecordProgress candidate = toProgress(
                        rs.getInt("ivl"),
                        rs.getInt("factor"),
                        rs.getInt("reps"),
                        rs.getInt("queue"),
                        rs.getInt("type")
                );
                if (candidate == null) {
                    continue;
                }
                ImportRecordProgress current = progress.get(noteId);
                if (current == null || isBetterProgress(candidate, current)) {
                    progress.put(noteId, candidate);
                }
            }
        } catch (SQLException ignored) {
        }
        return progress;
    }

    private ImportRecordProgress toProgress(int ivl, int factor, int reps, int queue, int type) {
        boolean suspended = queue == -1 || queue == -2;
        boolean isNew = type == 0 || queue == 0;
        if (isNew && !suspended) {
            return null;
        }
        double stability = Math.max(0.1, Math.abs((double) ivl));
        double ease = factor > 0 ? factor / 1000.0 : 2.5;
        double difficulty = (2.5 - ease) / 1.5;
        if (difficulty < 0) {
            difficulty = 0;
        } else if (difficulty > 1) {
            difficulty = 1;
        }
        int reviewCount = Math.max(0, reps);
        return new ImportRecordProgress(stability, difficulty, reviewCount, suspended);
    }

    private boolean isBetterProgress(ImportRecordProgress candidate, ImportRecordProgress current) {
        if (current.suspended() && !candidate.suspended()) {
            return true;
        }
        if (!current.suspended() && candidate.suspended()) {
            return false;
        }
        if (candidate.reviewCount() != current.reviewCount()) {
            return candidate.reviewCount() > current.reviewCount();
        }
        return Double.compare(candidate.stabilityDays(), current.stabilityDays()) > 0;
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
        private final Integer totalItems;
        private final Map<String, List<String>> modelFields;
        private final Map<Long, ImportRecordProgress> noteProgress;
        private final Map<String, String> mediaNameToIndex;

        private boolean hasNext;
        private boolean finished;

        ApkgImportStream(Path tempDir,
                         ZipFile zipFile,
                         Connection connection,
                         PreparedStatement statement,
                         ResultSet resultSet,
                         List<String> fields,
                         Integer totalItems,
                         Map<String, List<String>> modelFields,
                         Map<Long, ImportRecordProgress> noteProgress,
                         Map<String, String> mediaNameToIndex) throws IOException {
            this.tempDir = tempDir;
            this.zipFile = zipFile;
            this.connection = connection;
            this.statement = statement;
            this.resultSet = resultSet;
            this.fields = List.copyOf(fields);
            this.totalItems = totalItems;
            this.modelFields = modelFields;
            this.noteProgress = noteProgress;
            this.mediaNameToIndex = mediaNameToIndex;
            advance();
        }

        @Override
        public List<String> fields() {
            return fields;
        }

        @Override
        public Integer totalItems() {
            return totalItems;
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
                long noteId = resultSet.getLong("id");
                long mid = resultSet.getLong("mid");
                String flds = resultSet.getString("flds");
                Map<String, String> values = mapFields(String.valueOf(mid), flds);
                ImportRecordProgress progress = noteProgress.get(noteId);
                advance();
                return new ImportRecord(values, progress);
            } catch (SQLException ex) {
                finished = true;
                hasNext = false;
                return null;
            }
        }

        public ApkgMedia openMedia(String mediaName) throws IOException {
            String index = mediaNameToIndex.get(mediaName);
            if (index == null) {
                return null;
            }
            ZipEntry entry = zipFile.getEntry(index);
            if (entry == null) {
                return null;
            }
            long size = entry.getSize();
            if (size > 0) {
                return new ApkgMedia(zipFile.getInputStream(entry), size);
            }
            Path tempFile = tempDir.resolve("media-" + index);
            try (InputStream in = zipFile.getInputStream(entry)) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            long actualSize = Files.size(tempFile);
            if (actualSize <= 0) {
                return null;
            }
            return new ApkgMedia(Files.newInputStream(tempFile), actualSize);
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

    public record ApkgMedia(InputStream stream, long size) {
    }
}
