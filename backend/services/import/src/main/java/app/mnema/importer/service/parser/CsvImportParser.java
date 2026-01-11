package app.mnema.importer.service.parser;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CsvImportParser implements ImportParser {

    private final char delimiter;

    public CsvImportParser(char delimiter) {
        this.delimiter = delimiter;
    }

    @Override
    public ImportPreview preview(InputStream inputStream, int sampleSize) throws IOException {
        try (CsvImportStream stream = openStream(inputStream)) {
            List<ImportRecord> sample = new ArrayList<>();
            while (stream.hasNext() && sample.size() < sampleSize) {
                sample.add(stream.next());
            }
            return new ImportPreview(stream.fields(), sample);
        }
    }

    @Override
    public CsvImportStream openStream(InputStream inputStream) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();
        CSVParser parser = new CSVParser(new InputStreamReader(inputStream, StandardCharsets.UTF_8), format);
        return new CsvImportStream(parser);
    }

    public static class CsvImportStream implements ImportStream {
        private final CSVParser parser;
        private final Iterator<CSVRecord> iterator;
        private final List<String> headers;
        private final boolean usesHeader;
        private CSVRecord nextRecord;

        CsvImportStream(CSVParser parser) {
            this.parser = parser;
            this.iterator = parser.iterator();
            CSVRecord first = iterator.hasNext() ? iterator.next() : null;
            if (first == null) {
                this.headers = List.of();
                this.usesHeader = false;
                this.nextRecord = null;
                return;
            }
            List<String> candidate = recordToList(first);
            if (looksLikeHeader(candidate)) {
                this.headers = List.copyOf(candidate);
                this.usesHeader = true;
                this.nextRecord = null;
            } else {
                this.headers = List.copyOf(defaultHeaders(candidate.size()));
                this.usesHeader = false;
                this.nextRecord = first;
            }
        }

        @Override
        public List<String> fields() {
            return headers;
        }

        @Override
        public boolean hasNext() {
            return nextRecord != null || iterator.hasNext();
        }

        @Override
        public ImportRecord next() {
            CSVRecord record = nextRecord != null ? nextRecord : iterator.next();
            nextRecord = null;
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                String value = usesHeader ? record.get(header) : (i < record.size() ? record.get(i) : "");
                values.put(header, value);
            }
            return new ImportRecord(values, null);
        }

        @Override
        public void close() throws IOException {
            parser.close();
        }

        private List<String> recordToList(CSVRecord record) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < record.size(); i++) {
                values.add(record.get(i));
            }
            return values;
        }

        private boolean looksLikeHeader(List<String> values) {
            if (values.isEmpty()) {
                return false;
            }
            int likelyData = 0;
            int likelyHeader = 0;
            int identifierLike = 0;
            var seen = new java.util.HashSet<String>();
            for (String value : values) {
                String trimmed = value == null ? "" : value.trim();
                if (trimmed.isEmpty()) {
                    likelyData++;
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (!seen.add(lower)) {
                    likelyData++;
                }
                if (lower.length() > 60) {
                    likelyData++;
                    continue;
                }
                if (lower.contains("?") || lower.contains("!") || lower.contains(".") || lower.contains("«") || lower.contains("»")) {
                    likelyData++;
                    continue;
                }
                if (lower.equals("front") || lower.equals("back") || lower.equals("question") || lower.equals("answer")
                        || lower.equals("term") || lower.equals("definition") || lower.equals("word") || lower.equals("meaning")) {
                    likelyHeader++;
                }
                if (isIdentifierLike(trimmed)) {
                    identifierLike++;
                }
            }
            if (likelyData > 0) {
                return false;
            }
            if (likelyHeader > 0) {
                return true;
            }
            return identifierLike == values.size();
        }

        private boolean isIdentifierLike(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            if (value.length() > 40) {
                return false;
            }
            return value.matches("[\\p{L}0-9 _-]+");
        }

        private List<String> defaultHeaders(int count) {
            if (count == 2) {
                return List.of("front", "back");
            }
            List<String> headers = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                headers.add("Field " + i);
            }
            return headers;
        }
    }
}
