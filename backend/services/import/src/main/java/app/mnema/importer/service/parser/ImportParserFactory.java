package app.mnema.importer.service.parser;

import app.mnema.importer.domain.ImportSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ImportParserFactory {

    private final ObjectMapper objectMapper;

    public ImportParserFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ImportParser create(ImportSourceType sourceType) {
        return switch (sourceType) {
            case csv -> new CsvImportParser(',');
            case tsv -> new CsvImportParser('\t');
            case txt -> new TxtImportParser();
            case apkg -> new ApkgImportParser(objectMapper);
            case mnema -> new MnemaPackageImportParser(objectMapper);
        };
    }
}
