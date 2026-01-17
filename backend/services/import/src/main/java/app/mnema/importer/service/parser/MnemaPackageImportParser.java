package app.mnema.importer.service.parser;

import app.mnema.importer.client.core.CoreFieldTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MnemaPackageImportParser implements ImportParser {

    private static final String CSV_NAME = "deck.csv";
    private static final String MANIFEST_NAME = "deck.json";
    private static final String MEDIA_NAME = "media.json";
    private static final String MEDIA_DIR = "media/";

    private static final String ORDER_COLUMN = "__order";
    private static final String ANKI_FRONT_COLUMN = "__anki_front";
    private static final String ANKI_BACK_COLUMN = "__anki_back";
    private static final String ANKI_CSS_COLUMN = "__anki_css";
    private static final String ANKI_MODEL_ID_COLUMN = "__anki_modelId";
    private static final String ANKI_MODEL_NAME_COLUMN = "__anki_modelName";
    private static final String ANKI_TEMPLATE_NAME_COLUMN = "__anki_templateName";

    private static final Set<String> RESERVED_COLUMNS = Set.of(
            ORDER_COLUMN,
            ANKI_FRONT_COLUMN,
            ANKI_BACK_COLUMN,
            ANKI_CSS_COLUMN,
            ANKI_MODEL_ID_COLUMN,
            ANKI_MODEL_NAME_COLUMN,
            ANKI_TEMPLATE_NAME_COLUMN
    );

    private final ObjectMapper objectMapper;

    public MnemaPackageImportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportPreview preview(InputStream inputStream, int sampleSize) throws IOException {
        try (MnemaPackageImportStream stream = openStream(inputStream)) {
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
    public MnemaPackageImportStream openStream(InputStream inputStream) throws IOException {
        Path tempDir = Files.createTempDirectory("mnema-package-");
        Path zipPath = tempDir.resolve("package.zip");
        Files.copy(inputStream, zipPath, StandardCopyOption.REPLACE_EXISTING);
        ZipFile zipFile = new ZipFile(zipPath.toFile());

        ZipEntry csvEntry = zipFile.getEntry(CSV_NAME);
        if (csvEntry == null) {
            closeQuietly(zipFile);
            throw new IOException("Missing deck.csv in mnema package");
        }

        MnemaPackageManifest manifest = readManifest(zipFile);
        Map<String, String> mediaMap = readMediaMap(zipFile);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();
        CSVParser parser = new CSVParser(
                new InputStreamReader(zipFile.getInputStream(csvEntry), StandardCharsets.UTF_8),
                format
        );

        return new MnemaPackageImportStream(tempDir, zipFile, parser, manifest, mediaMap);
    }

    private MnemaPackageManifest readManifest(ZipFile zipFile) {
        ZipEntry entry = zipFile.getEntry(MANIFEST_NAME);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zipFile.getInputStream(entry)) {
            return objectMapper.readValue(in, MnemaPackageManifest.class);
        } catch (IOException ex) {
            return null;
        }
    }

    private Map<String, String> readMediaMap(ZipFile zipFile) {
        ZipEntry entry = zipFile.getEntry(MEDIA_NAME);
        if (entry == null) {
            return Map.of();
        }
        try (InputStream in = zipFile.getInputStream(entry)) {
            JsonNode node = objectMapper.readTree(in);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, String> map = new HashMap<>();
            node.fields().forEachRemaining(field -> {
                String id = field.getKey();
                JsonNode fileName = field.getValue().path("fileName");
                if (!id.isBlank() && fileName.isTextual()) {
                    map.put(id, fileName.asText());
                }
            });
            return map;
        } catch (IOException ex) {
            return Map.of();
        }
    }

    private void closeQuietly(ZipFile zipFile) {
        try {
            zipFile.close();
        } catch (IOException ignored) {
        }
    }

    public static class MnemaPackageImportStream implements MediaImportStream, TemplateAwareImportStream {

        private final Path tempDir;
        private final ZipFile zipFile;
        private final CSVParser parser;
        private final Iterator<CSVRecord> iterator;
        private final List<String> fields;
        private final ImportLayout layout;
        private final boolean anki;
        private final List<CoreFieldTemplate> templateFields;
        private final Map<String, String> mediaMap;

        MnemaPackageImportStream(Path tempDir,
                                 ZipFile zipFile,
                                 CSVParser parser,
                                 MnemaPackageManifest manifest,
                                 Map<String, String> mediaMap) {
            this.tempDir = tempDir;
            this.zipFile = zipFile;
            this.parser = parser;
            this.iterator = parser.iterator();
            this.mediaMap = mediaMap == null ? Map.of() : mediaMap;

            List<String> headers = parser.getHeaderNames();
            List<String> headerFields = headers == null ? List.of() : headers.stream()
                    .filter(name -> !RESERVED_COLUMNS.contains(name))
                    .toList();

            TemplateMetadata template = manifest == null ? null : TemplateMetadata.from(manifest);
            List<String> manifestFields = template == null ? List.of() : template.fieldNames();
            Set<String> headerSet = new HashSet<>(headerFields);

            List<String> mergedFields = new ArrayList<>();
            for (String name : manifestFields) {
                if (headerSet.contains(name) && !mergedFields.contains(name)) {
                    mergedFields.add(name);
                }
            }
            if (mergedFields.isEmpty()) {
                mergedFields = new ArrayList<>(headerFields);
            }

            this.fields = List.copyOf(mergedFields);
            this.layout = template == null ? null : template.layout();
            this.anki = template != null && template.anki();
            this.templateFields = template == null ? List.of() : template.fields();
        }

        @Override
        public List<String> fields() {
            return fields;
        }

        @Override
        public Integer totalItems() {
            return null;
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
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public ImportRecord next() {
            if (!iterator.hasNext()) {
                return null;
            }
            CSVRecord record = iterator.next();
            Map<String, String> values = new LinkedHashMap<>();
            for (String field : fields) {
                if (!record.isMapped(field)) {
                    values.put(field, "");
                    continue;
                }
                values.put(field, record.get(field));
            }

            Integer orderIndex = parseOrder(record);
            ImportAnkiTemplate ankiTemplate = buildAnkiTemplate(record);
            return new ImportRecord(values, null, ankiTemplate, orderIndex);
        }

        @Override
        public ImportMedia openMedia(String mediaName) throws IOException {
            if (mediaName == null || mediaName.isBlank()) {
                return null;
            }
            String normalized = mediaName.trim();
            if (normalized.startsWith("mnema-media://")) {
                normalized = normalized.substring("mnema-media://".length());
            }
            String fileName = mediaMap.getOrDefault(normalized, normalized);
            ZipEntry entry = zipFile.getEntry(MEDIA_DIR + fileName);
            if (entry == null) {
                return null;
            }
            long size = entry.getSize();
            if (size > 0) {
                return new ImportMedia(zipFile.getInputStream(entry), size);
            }
            Path tempFile = tempDir.resolve("media-" + normalized);
            try (InputStream in = zipFile.getInputStream(entry)) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            long actualSize = Files.size(tempFile);
            if (actualSize <= 0) {
                return null;
            }
            return new ImportMedia(Files.newInputStream(tempFile), actualSize);
        }

        @Override
        public List<CoreFieldTemplate> templateFields() {
            return templateFields;
        }

        @Override
        public void close() throws IOException {
            try {
                parser.close();
            } catch (IOException ignored) {
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

        private Integer parseOrder(CSVRecord record) {
            if (!record.isMapped(ORDER_COLUMN)) {
                return null;
            }
            String raw = record.get(ORDER_COLUMN);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private ImportAnkiTemplate buildAnkiTemplate(CSVRecord record) {
            String front = readColumn(record, ANKI_FRONT_COLUMN);
            String back = readColumn(record, ANKI_BACK_COLUMN);
            String css = readColumn(record, ANKI_CSS_COLUMN);
            if ((front == null || front.isBlank()) && (back == null || back.isBlank())) {
                return null;
            }
            String modelId = readColumn(record, ANKI_MODEL_ID_COLUMN);
            String modelName = readColumn(record, ANKI_MODEL_NAME_COLUMN);
            String templateName = readColumn(record, ANKI_TEMPLATE_NAME_COLUMN);
            return new ImportAnkiTemplate(
                    emptyToNull(modelId),
                    emptyToNull(modelName),
                    emptyToNull(templateName),
                    front == null ? "" : front,
                    back == null ? "" : back,
                    css == null ? "" : css
            );
        }

        private String readColumn(CSVRecord record, String name) {
            return record.isMapped(name) ? record.get(name) : null;
        }

        private String emptyToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
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

        List<String> fieldNames() {
            if (fields == null || fields.isEmpty()) {
                return List.of();
            }
            return fields.stream().map(CoreFieldTemplate::name).toList();
        }
    }
}
