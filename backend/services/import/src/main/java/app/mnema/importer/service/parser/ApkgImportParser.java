package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkgImportParser implements ImportParser {

    private static final String COLLECTION_V21B_NAME = "collection.anki21b";
    private static final String COLLECTION_V21_NAME = "collection.anki21";
    private static final String COLLECTION_V2_NAME = "collection.anki2";
    private static final String MEDIA_NAME = "media";
    private static final int PREVIEW_SCAN_LIMIT = 60;
    private static final String ANKI_UPDATE_MESSAGE = "please update to the latest anki version";
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final java.util.regex.Pattern TEMPLATE_FIELD_PATTERN = java.util.regex.Pattern.compile("\\{\\{([^}]+)}}");
    private static final Set<String> BUILTIN_FIELDS = Set.of(
            "frontside",
            "tags",
            "deck",
            "subdeck",
            "card",
            "cardid",
            "note",
            "noteid",
            "notetype",
            "type"
    );

    private final ObjectMapper objectMapper;

    public ApkgImportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportPreview preview(InputStream inputStream, int sampleSize) throws IOException {
        try (ApkgImportStream stream = openStream(inputStream)) {
            int scanLimit = Math.max(sampleSize * 10, PREVIEW_SCAN_LIMIT);
            List<ScoredRecord> scored = new ArrayList<>();
            int index = 0;
            while (stream.hasNext() && index < scanLimit) {
                ImportRecord record = stream.next();
                if (record != null) {
                    scored.add(scoreRecord(record, index));
                }
                index++;
            }
            List<ImportRecord> sample = pickSample(scored, sampleSize);
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
        ImportLayout layout = loadTemplateLayout(connection, modelFields);
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
                    mediaNameToIndex,
                    layout
            );
        } catch (SQLException ex) {
            closeQuietly(connection);
            closeQuietly(zipFile);
            throw new IOException("Failed to query notes", ex);
        }
    }

    private Path extractCollection(ZipFile zipFile, Path tempDir) throws IOException {
        ZipEntry entry = zipFile.getEntry(COLLECTION_V21B_NAME);
        String chosenName = COLLECTION_V21B_NAME;
        if (entry == null) {
            entry = zipFile.getEntry(COLLECTION_V21_NAME);
            chosenName = COLLECTION_V21_NAME;
        }
        if (entry == null) {
            entry = zipFile.getEntry(COLLECTION_V2_NAME);
            chosenName = COLLECTION_V2_NAME;
        }
        if (entry == null) {
            throw new IOException("Missing collection.anki21b/collection.anki21/collection.anki2 in apkg");
        }
        if (COLLECTION_V21B_NAME.equals(chosenName)) {
            Path collectionFile = tempDir.resolve(COLLECTION_V21_NAME);
            try (InputStream in = zipFile.getInputStream(entry);
                 ZstdInputStream zstd = new ZstdInputStream(in)) {
                Files.copy(zstd, collectionFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return collectionFile;
        }
        Path collectionFile = tempDir.resolve(chosenName);
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
            try {
                JsonNode node = objectMapper.readTree(json);
                Map<String, String> map = new HashMap<>();
                node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
                return map;
            } catch (IOException ex) {
                return Map.of();
            }
        }
    }

    private Map<String, List<String>> loadModelFields(Connection connection) throws IOException {
        try (PreparedStatement stmt = connection.prepareStatement("select models from col limit 1")) {
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) {
                return fallbackFields(connection);
            }
            String json = rs.getString(1);
            if (json == null || json.isBlank()) {
                Map<String, List<String>> byTables = loadFieldsTable(connection);
                return byTables.isEmpty() ? fallbackFields(connection) : byTables;
            }
            JsonNode node = objectMapper.readTree(json);
            Map<String, List<String>> modelFields = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                JsonNode fieldsNode = entry.getValue().path("flds");
                List<String> fields = new ArrayList<>();
                for (JsonNode f : fieldsNode) {
                    String name = f.path("name").asText();
                    if (!name.isBlank()) {
                        fields.add(name);
                    }
                }
                if (!fields.isEmpty()) {
                    modelFields.put(entry.getKey(), fields);
                }
            });
            if (modelFields.isEmpty()) {
                Map<String, List<String>> byTables = loadFieldsTable(connection);
                return byTables.isEmpty() ? fallbackFields(connection) : byTables;
            }
            return modelFields;
        } catch (SQLException | IOException ex) {
            Map<String, List<String>> byTables = loadFieldsTable(connection);
            if (!byTables.isEmpty()) {
                return byTables;
            }
            Map<String, List<String>> fallback = fallbackFields(connection);
            if (!fallback.isEmpty()) {
                return fallback;
            }
            throw new IOException("Failed to read models", ex);
        }
    }

    private Map<String, List<String>> loadFieldsTable(Connection connection) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "select ntid, ord, name from fields order by ntid, ord")) {
            ResultSet rs = stmt.executeQuery();
            Map<String, List<String>> modelFields = new LinkedHashMap<>();
            while (rs.next()) {
                long ntid = rs.getLong("ntid");
                String name = rs.getString("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                modelFields.computeIfAbsent(String.valueOf(ntid), key -> new ArrayList<>()).add(name);
            }
            return modelFields;
        } catch (SQLException ex) {
            return Map.of();
        }
    }

    private Map<String, List<String>> fallbackFields(Connection connection) {
        try (PreparedStatement stmt = connection.prepareStatement("select flds from notes limit 25")) {
            ResultSet rs = stmt.executeQuery();
            int maxFields = 0;
            while (rs.next()) {
                String flds = rs.getString(1);
                if (flds == null) {
                    continue;
                }
                int count = flds.split(FIELD_SEPARATOR, -1).length;
                if (count > maxFields) {
                    maxFields = count;
                }
            }
            if (maxFields <= 0) {
                return Map.of();
            }
            List<String> fields = new ArrayList<>();
            for (int i = 1; i <= maxFields; i++) {
                fields.add("Field " + i);
            }
            return Map.of("default", fields);
        } catch (SQLException ex) {
            return Map.of();
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
        Set<String> union = new LinkedHashSet<>();
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

    private ImportLayout loadTemplateLayout(Connection connection, Map<String, List<String>> modelFields) {
        Map<String, String> normalizedFields = normalizedFieldMap(modelFields);
        List<String> front = new ArrayList<>();
        List<String> back = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(
                "select config from templates order by ntid, ord")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                byte[] config = rs.getBytes("config");
                if (config == null || config.length == 0) {
                    continue;
                }
                TemplateStrings templates = decodeTemplateConfig(config);
                if (templates == null) {
                    continue;
                }
                addUnique(front, extractTemplateFields(templates.front(), normalizedFields));
                addUnique(back, extractTemplateFields(templates.back(), normalizedFields));
            }
        } catch (SQLException ex) {
            return null;
        }

        if (front.isEmpty() && back.isEmpty()) {
            return null;
        }
        return new ImportLayout(List.copyOf(front), List.copyOf(back));
    }

    private TemplateStrings decodeTemplateConfig(byte[] config) {
        List<String> withFields = new ArrayList<>();
        int index = 0;
        while (index < config.length) {
            Varint key = readVarint(config, index);
            if (key == null) {
                break;
            }
            int wireType = (int) (key.value() & 0x7);
            index = key.nextIndex();
            switch (wireType) {
                case 2 -> {
                    Varint lenVar = readVarint(config, index);
                    if (lenVar == null) {
                        return null;
                    }
                    int len = (int) lenVar.value();
                    index = lenVar.nextIndex();
                    if (len < 0 || index + len > config.length) {
                        return null;
                    }
                    String text = new String(config, index, len, StandardCharsets.UTF_8);
                    if (text.contains("{{")) {
                        withFields.add(text);
                    }
                    index += len;
                }
                case 0 -> {
                    Varint skip = readVarint(config, index);
                    if (skip == null) {
                        return null;
                    }
                    index = skip.nextIndex();
                }
                case 1 -> index += 8;
                case 5 -> index += 4;
                default -> index = config.length;
            }
        }
        if (withFields.isEmpty()) {
            return null;
        }
        String front = withFields.getFirst();
        String back = withFields.size() > 1 ? withFields.get(1) : null;
        return new TemplateStrings(front, back);
    }

    private Varint readVarint(byte[] data, int offset) {
        long result = 0;
        int shift = 0;
        int index = offset;
        while (index < data.length && shift < 64) {
            int b = data[index] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            index++;
            if ((b & 0x80) == 0) {
                return new Varint(result, index);
            }
            shift += 7;
        }
        return null;
    }

    private List<String> extractTemplateFields(String template, Map<String, String> normalizedFields) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        var matcher = TEMPLATE_FIELD_PATTERN.matcher(template);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token == null) {
                continue;
            }
            String cleaned = cleanTemplateToken(token);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            String normalized = normalizeField(cleaned);
            if (normalized.isBlank() || BUILTIN_FIELDS.contains(normalized)) {
                continue;
            }
            String resolved = normalizedFields.getOrDefault(normalized, cleaned);
            if (!result.contains(resolved)) {
                result.add(resolved);
            }
        }
        return result;
    }

    private String cleanTemplateToken(String token) {
        String cleaned = token.trim();
        while (!cleaned.isEmpty()) {
            char c = cleaned.charAt(0);
            if (c == '#' || c == '^' || c == '/') {
                cleaned = cleaned.substring(1).trim();
            } else {
                break;
            }
        }
        int idx = cleaned.lastIndexOf(':');
        if (idx >= 0 && idx < cleaned.length() - 1) {
            cleaned = cleaned.substring(idx + 1).trim();
        }
        return cleaned;
    }

    private Map<String, String> normalizedFieldMap(Map<String, List<String>> modelFields) {
        Map<String, String> normalized = new HashMap<>();
        for (List<String> fields : modelFields.values()) {
            for (String field : fields) {
                if (field == null || field.isBlank()) {
                    continue;
                }
                normalized.putIfAbsent(normalizeField(field), field);
            }
        }
        return normalized;
    }

    private String normalizeField(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private void addUnique(List<String> target, List<String> values) {
        for (String value : values) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
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

    private List<ImportRecord> pickSample(List<ScoredRecord> scored, int sampleSize) {
        if (scored.isEmpty() || sampleSize <= 0) {
            return List.of();
        }
        List<ScoredRecord> candidates = scored.stream()
                .filter(record -> record.score() > 0)
                .toList();
        if (candidates.isEmpty()) {
            candidates = scored;
        }
        return candidates.stream()
                .sorted(java.util.Comparator
                        .comparingInt(ScoredRecord::score)
                        .reversed()
                        .thenComparingInt(ScoredRecord::index))
                .limit(sampleSize)
                .map(ScoredRecord::record)
                .toList();
    }

    private ScoredRecord scoreRecord(ImportRecord record, int index) {
        if (record == null || record.fields() == null || record.fields().isEmpty()) {
            return new ScoredRecord(record, 0, index);
        }
        int nonEmpty = 0;
        int totalLength = 0;
        boolean updateNotice = false;
        for (String value : record.fields().values()) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonEmpty++;
            totalLength += Math.min(trimmed.length(), 500);
            if (!updateNotice && trimmed.toLowerCase(java.util.Locale.ROOT).contains(ANKI_UPDATE_MESSAGE)) {
                updateNotice = true;
            }
        }
        if (nonEmpty == 0) {
            return new ScoredRecord(record, 0, index);
        }
        if (updateNotice && nonEmpty == 1) {
            return new ScoredRecord(record, 0, index);
        }
        int score = nonEmpty * 1000 + totalLength;
        return new ScoredRecord(record, score, index);
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

    private record Varint(long value, int nextIndex) {
    }

    private record TemplateStrings(String front, String back) {
    }

    private record ScoredRecord(ImportRecord record, int score, int index) {
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
        private final ImportLayout layout;

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
                         Map<String, String> mediaNameToIndex,
                         ImportLayout layout) throws IOException {
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
            this.layout = layout;
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
        public ImportLayout layout() {
            return layout;
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
