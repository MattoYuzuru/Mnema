package app.mnema.importer.service.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class TxtImportParser implements ImportParser {

    @Override
    public ImportPreview preview(InputStream inputStream, int sampleSize) throws IOException {
        try (TxtImportStream stream = openStream(inputStream)) {
            var sample = new java.util.ArrayList<ImportRecord>();
            while (stream.hasNext() && sample.size() < sampleSize) {
                sample.add(stream.next());
            }
            return new ImportPreview(stream.fields(), sample);
        }
    }

    @Override
    public TxtImportStream openStream(InputStream inputStream) {
        return new TxtImportStream(new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)));
    }

    public static class TxtImportStream implements ImportStream {
        private final BufferedReader reader;
        private String nextLine;

        TxtImportStream(BufferedReader reader) {
            this.reader = reader;
            advance();
        }

        @Override
        public List<String> fields() {
            return List.of("text");
        }

        @Override
        public boolean hasNext() {
            return nextLine != null;
        }

        @Override
        public ImportRecord next() {
            String value = nextLine;
            advance();
            return new ImportRecord(Map.of("text", value), null);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }

        private void advance() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        nextLine = line;
                        return;
                    }
                }
                nextLine = null;
            } catch (IOException ex) {
                nextLine = null;
            }
        }
    }
}
