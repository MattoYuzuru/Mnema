package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
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

        Map<String, AnkiNoteType> noteTypes = loadNoteTypes(connection);
        Set<String> usedNoteTypes = loadUsedNoteTypeIds(connection);
        if (!noteTypes.isEmpty() && !usedNoteTypes.isEmpty()) {
            Map<String, AnkiNoteType> filtered = noteTypes.entrySet().stream()
                    .filter(entry -> usedNoteTypes.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));
            if (!filtered.isEmpty()) {
                noteTypes = filtered;
            }
        }
        Map<String, List<String>> modelFields = noteTypes.isEmpty() ? loadModelFields(connection) : noteTypeFields(noteTypes);
        if (!modelFields.isEmpty() && !usedNoteTypes.isEmpty()) {
            Map<String, List<String>> filteredFields = modelFields.entrySet().stream()
                    .filter(entry -> usedNoteTypes.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));
            if (!filteredFields.isEmpty()) {
                modelFields = filteredFields;
            }
        }
        ImportLayout layout = buildTemplateLayout(noteTypes, modelFields);
        List<String> unionFields = unionFields(modelFields);
        int totalItems = countNotes(connection);
        Map<Long, NoteMeta> noteMeta = loadNoteMeta(connection);

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
                    noteMeta,
                    mediaNameToIndex,
                    layout,
                    noteTypes
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
            byte[] payload = in.readAllBytes();
            if (payload.length == 0) {
                return Map.of();
            }
            Map<String, String> jsonMap = parseMediaJson(payload);
            if (jsonMap != null) {
                return jsonMap;
            }
            byte[] decoded = maybeDecompressMedia(payload);
            List<String> indices = listMediaIndices(zipFile);
            Map<String, String> protoMap = parseMediaProto(decoded, indices);
            return protoMap.isEmpty() ? Map.of() : protoMap;
        }
    }

    private Map<String, String> parseMediaJson(byte[] payload) {
        String json = new String(payload, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Map<String, String> map = new HashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
            return map;
        } catch (IOException ex) {
            return null;
        }
    }

    private byte[] maybeDecompressMedia(byte[] payload) {
        if (!isZstdFrame(payload)) {
            return payload;
        }
        try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(payload))) {
            return zstd.readAllBytes();
        } catch (IOException ex) {
            return payload;
        }
    }

    private List<String> listMediaIndices(ZipFile zipFile) {
        List<String> indices = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name != null && name.matches("\\d+")) {
                indices.add(name);
            }
        }
        indices.sort(Comparator.comparingLong(value -> {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ex) {
                return Long.MAX_VALUE;
            }
        }));
        return indices;
    }

    private static boolean isZstdFrame(byte[] payload) {
        return payload.length >= 4
                && payload[0] == 0x28
                && payload[1] == (byte) 0xB5
                && payload[2] == 0x2F
                && payload[3] == (byte) 0xFD;
    }

    private Map<String, String> parseMediaProto(byte[] payload, List<String> indices) {
        if (payload == null || payload.length == 0) {
            return Map.of();
        }
        List<String> names = new ArrayList<>();
        int index = 0;
        while (index < payload.length) {
            Varint key = readVarint(payload, index);
            if (key == null) {
                break;
            }
            int wireType = (int) (key.value() & 0x7);
            index = key.nextIndex();
            switch (wireType) {
                case 2 -> {
                    Varint lenVar = readVarint(payload, index);
                    if (lenVar == null) {
                        return Map.of();
                    }
                    int len = (int) lenVar.value();
                    index = lenVar.nextIndex();
                    if (len < 0 || index + len > payload.length) {
                        return Map.of();
                    }
                    String name = parseMediaEntryName(payload, index, len);
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                    index += len;
                }
                case 0 -> {
                    Varint skip = readVarint(payload, index);
                    if (skip == null) {
                        return Map.of();
                    }
                    index = skip.nextIndex();
                }
                case 1 -> index += 8;
                case 5 -> index += 4;
                default -> index = payload.length;
            }
        }
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        if (indices != null && indices.size() == names.size()) {
            for (int i = 0; i < names.size(); i++) {
                map.put(indices.get(i), names.get(i));
            }
            return map;
        }
        for (int i = 0; i < names.size(); i++) {
            map.put(String.valueOf(i), names.get(i));
        }
        return map;
    }

    private String parseMediaEntryName(byte[] payload, int offset, int length) {
        int index = offset;
        int end = offset + length;
        while (index < end) {
            Varint key = readVarint(payload, index);
            if (key == null) {
                return null;
            }
            int field = (int) (key.value() >> 3);
            int wireType = (int) (key.value() & 0x7);
            index = key.nextIndex();
            switch (wireType) {
                case 2 -> {
                    Varint lenVar = readVarint(payload, index);
                    if (lenVar == null) {
                        return null;
                    }
                    int len = (int) lenVar.value();
                    index = lenVar.nextIndex();
                    if (len < 0 || index + len > end) {
                        return null;
                    }
                    if (field == 1) {
                        return new String(payload, index, len, StandardCharsets.UTF_8);
                    }
                    index += len;
                }
                case 0 -> {
                    Varint skip = readVarint(payload, index);
                    if (skip == null) {
                        return null;
                    }
                    index = skip.nextIndex();
                }
                case 1 -> index += 8;
                case 5 -> index += 4;
                default -> index = end;
            }
        }
        return null;
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

    private Set<String> loadUsedNoteTypeIds(Connection connection) {
        Set<String> ids = new LinkedHashSet<>();
        try (PreparedStatement stmt = connection.prepareStatement("select distinct mid from notes")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                ids.add(String.valueOf(rs.getLong("mid")));
            }
        } catch (SQLException ignored) {
        }
        return ids;
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

    private Map<String, List<String>> noteTypeFields(Map<String, AnkiNoteType> noteTypes) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (AnkiNoteType type : noteTypes.values()) {
            if (type.fields() != null && !type.fields().isEmpty()) {
                fields.put(type.id(), type.fields());
            }
        }
        return fields;
    }

    private Map<String, AnkiNoteType> loadNoteTypes(Connection connection) {
        try (PreparedStatement stmt = connection.prepareStatement("select models from col limit 1")) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String json = rs.getString(1);
                if (json != null && !json.isBlank()) {
                    Map<String, AnkiNoteType> fromJson = parseNoteTypesFromModels(json);
                    if (!fromJson.isEmpty()) {
                        return fromJson;
                    }
                }
            }
        } catch (SQLException | IOException ignored) {
        }
        return loadNoteTypesFromTables(connection);
    }

    private Map<String, AnkiNoteType> parseNoteTypesFromModels(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        Map<String, AnkiNoteType> noteTypes = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode model = entry.getValue();
            List<String> fields = new ArrayList<>();
            for (JsonNode f : model.path("flds")) {
                String name = f.path("name").asText();
                if (!name.isBlank()) {
                    fields.add(name);
                }
            }
            List<AnkiTemplateConfig> templates = new ArrayList<>();
            for (JsonNode t : model.path("tmpls")) {
                String front = t.path("qfmt").asText(null);
                String back = t.path("afmt").asText(null);
                if (front == null && back == null) {
                    continue;
                }
                int ord = t.path("ord").asInt(0);
                String name = t.path("name").asText(null);
                templates.add(new AnkiTemplateConfig(ord, name, front, back));
            }
            if (!fields.isEmpty()) {
                String name = model.path("name").asText(null);
                String css = model.path("css").asText("");
                noteTypes.put(entry.getKey(), new AnkiNoteType(entry.getKey(), name, fields, templates, css));
            }
        });
        return noteTypes;
    }

    private Map<String, AnkiNoteType> loadNoteTypesFromTables(Connection connection) {
        Map<String, List<String>> fields = loadFieldsTable(connection);
        if (fields.isEmpty()) {
            return Map.of();
        }
        Map<String, String> cssByType = loadNotetypeCss(connection);
        Map<String, List<AnkiTemplateConfig>> templates = loadTemplatesTable(connection);
        Map<String, AnkiNoteType> noteTypes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            String id = entry.getKey();
            List<AnkiTemplateConfig> typeTemplates = templates.getOrDefault(id, List.of());
            String css = cssByType.getOrDefault(id, "");
            noteTypes.put(id, new AnkiNoteType(id, null, entry.getValue(), typeTemplates, css));
        }
        return noteTypes;
    }

    private Map<String, String> loadNotetypeCss(Connection connection) {
        Map<String, String> cssByType = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement("select id, config from notetypes")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                byte[] config = rs.getBytes("config");
                if (config == null || config.length == 0) {
                    continue;
                }
                String css = extractCssFromProto(config);
                if (css != null && !css.isBlank()) {
                    cssByType.put(String.valueOf(rs.getLong("id")), css);
                }
            }
        } catch (SQLException ignored) {
        }
        return cssByType;
    }

    private Map<String, List<AnkiTemplateConfig>> loadTemplatesTable(Connection connection) {
        Map<String, List<AnkiTemplateConfig>> templates = new LinkedHashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "select ntid, ord, name, config from templates order by ntid, ord")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                byte[] config = rs.getBytes("config");
                if (config == null || config.length == 0) {
                    continue;
                }
                TemplateStrings decoded = decodeTemplateConfig(config);
                if (decoded == null) {
                    continue;
                }
                String ntid = String.valueOf(rs.getLong("ntid"));
                int ord = rs.getInt("ord");
                String name = rs.getString("name");
                templates.computeIfAbsent(ntid, key -> new ArrayList<>())
                        .add(new AnkiTemplateConfig(ord, name, decoded.front(), decoded.back()));
            }
        } catch (SQLException ignored) {
        }
        return templates;
    }

    private ImportLayout buildTemplateLayout(Map<String, AnkiNoteType> noteTypes,
                                             Map<String, List<String>> modelFields) {
        if (noteTypes.isEmpty()) {
            return null;
        }
        Map<String, String> normalizedFields = normalizedFieldMap(modelFields);
        List<String> front = new ArrayList<>();
        List<String> back = new ArrayList<>();
        for (AnkiNoteType noteType : noteTypes.values()) {
            AnkiTemplateConfig template = pickPrimaryTemplate(noteType.templates());
            if (template == null) {
                continue;
            }
            addUnique(front, extractTemplateFields(template.front(), normalizedFields));
            addUnique(back, extractTemplateFields(template.back(), normalizedFields));
        }

        if (front.isEmpty() && back.isEmpty()) {
            return null;
        }
        return new ImportLayout(List.copyOf(front), List.copyOf(back));
    }

    private static AnkiTemplateConfig pickPrimaryTemplate(List<AnkiTemplateConfig> templates) {
        if (templates == null || templates.isEmpty()) {
            return null;
        }
        return templates.stream()
                .min(Comparator.comparingInt(AnkiTemplateConfig::ord))
                .orElse(null);
    }

    private static ImportAnkiTemplate toImportTemplate(AnkiNoteType noteType) {
        if (noteType == null) {
            return null;
        }
        AnkiTemplateConfig template = pickPrimaryTemplate(noteType.templates());
        if (template == null) {
            return null;
        }
        return new ImportAnkiTemplate(
                noteType.id(),
                noteType.name(),
                template.name(),
                template.front(),
                template.back(),
                noteType.css()
        );
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

    private String extractCssFromProto(byte[] config) {
        List<String> candidates = new ArrayList<>();
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
                    if (text.contains(".card") || text.contains("@font-face") || text.contains("font-family")) {
                        candidates.add(text);
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
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingInt(String::length).reversed());
        return candidates.getFirst();
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
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private void addUnique(List<String> target, List<String> values) {
        for (String value : values) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private Map<Long, NoteMeta> loadNoteMeta(Connection connection) {
        Map<Long, ImportRecordProgress> progress = new HashMap<>();
        Map<Long, Long> orderKeys = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "select id, nid, ivl, factor, reps, queue, type from cards")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                long cardId = rs.getLong("id");
                long noteId = rs.getLong("nid");
                orderKeys.merge(noteId, cardId, Math::min);
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
        if (orderKeys.isEmpty()) {
            return progress.isEmpty() ? Map.of() : progress.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> new NoteMeta(entry.getValue(), null)
                    ));
        }
        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(orderKeys.entrySet());
        sorted.sort(Comparator.comparingLong(Map.Entry::getValue));
        Map<Long, NoteMeta> meta = new HashMap<>();
        int index = 0;
        for (Map.Entry<Long, Long> entry : sorted) {
            ImportRecordProgress noteProgress = progress.get(entry.getKey());
            meta.put(entry.getKey(), new NoteMeta(noteProgress, index));
            index++;
        }
        return meta;
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

    private record AnkiTemplateConfig(int ord, String name, String front, String back) {
    }

    private record AnkiNoteType(String id, String name, List<String> fields, List<AnkiTemplateConfig> templates, String css) {
    }

    private record NoteMeta(ImportRecordProgress progress, Integer orderIndex) {
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

    public static class ApkgImportStream implements MediaImportStream {

        private final Path tempDir;
        private final ZipFile zipFile;
        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet resultSet;
        private final List<String> fields;
        private final Integer totalItems;
        private final Map<String, List<String>> modelFields;
        private final Map<Long, NoteMeta> noteMeta;
        private final Map<String, String> mediaNameToIndex;
        private final ImportLayout layout;
        private final Map<String, AnkiNoteType> noteTypes;

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
                         Map<Long, NoteMeta> noteMeta,
                         Map<String, String> mediaNameToIndex,
                         ImportLayout layout,
                         Map<String, AnkiNoteType> noteTypes) throws IOException {
            this.tempDir = tempDir;
            this.zipFile = zipFile;
            this.connection = connection;
            this.statement = statement;
            this.resultSet = resultSet;
            this.fields = List.copyOf(fields);
            this.totalItems = totalItems;
            this.modelFields = modelFields;
            this.noteMeta = noteMeta;
            this.mediaNameToIndex = mediaNameToIndex;
            this.layout = layout;
            this.noteTypes = noteTypes;
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
        public boolean isAnki() {
            return noteTypes != null && !noteTypes.isEmpty();
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
                NoteMeta meta = noteMeta.get(noteId);
                ImportRecordProgress progress = meta == null ? null : meta.progress();
                Integer orderIndex = meta == null ? null : meta.orderIndex();
                ImportAnkiTemplate template = toImportTemplate(noteTypes.get(String.valueOf(mid)));
                advance();
                return new ImportRecord(values, progress, template, orderIndex);
            } catch (SQLException ex) {
                finished = true;
                hasNext = false;
                return null;
            }
        }

        public ImportMedia openMedia(String mediaName) throws IOException {
            String index = mediaNameToIndex.get(mediaName);
            if (index == null) {
                return null;
            }
            ZipEntry entry = zipFile.getEntry(index);
            if (entry == null) {
                return null;
            }
            InputStream raw = zipFile.getInputStream(entry);
            BufferedInputStream buffered = new BufferedInputStream(raw);
            buffered.mark(4);
            byte[] header = buffered.readNBytes(4);
            buffered.reset();

            if (isZstdFrame(header)) {
                Path tempFile = tempDir.resolve("media-" + index);
                try (ZstdInputStream zstd = new ZstdInputStream(buffered)) {
                    Files.copy(zstd, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                long actualSize = Files.size(tempFile);
                if (actualSize <= 0) {
                    return null;
                }
                return new ImportMedia(Files.newInputStream(tempFile), actualSize);
            }

            long size = entry.getSize();
            if (size > 0) {
                return new ImportMedia(buffered, size);
            }
            Path tempFile = tempDir.resolve("media-" + index);
            try (InputStream in = buffered) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            long actualSize = Files.size(tempFile);
            if (actualSize <= 0) {
                return null;
            }
            return new ImportMedia(Files.newInputStream(tempFile), actualSize);
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
