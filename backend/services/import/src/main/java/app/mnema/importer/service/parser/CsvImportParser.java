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
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        CSVParser parser = new CSVParser(new InputStreamReader(inputStream, StandardCharsets.UTF_8), format);
        return new CsvImportStream(parser);
    }

    public static class CsvImportStream implements ImportStream {
        private final CSVParser parser;
        private final Iterator<CSVRecord> iterator;
        private final List<String> headers;

        CsvImportStream(CSVParser parser) {
            this.parser = parser;
            this.iterator = parser.iterator();
            this.headers = List.copyOf(parser.getHeaderNames());
        }

        @Override
        public List<String> fields() {
            return headers;
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public ImportRecord next() {
            CSVRecord record = iterator.next();
            Map<String, String> values = new LinkedHashMap<>();
            for (String header : headers) {
                values.put(header, record.get(header));
            }
            return new ImportRecord(values, null);
        }

        @Override
        public void close() throws IOException {
            parser.close();
        }
    }
}
