package app.mnema.importer.service.parser;

import app.mnema.importer.domain.ImportSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ImportParserFactoryTest {

    private final ImportParserFactory factory = new ImportParserFactory(new ObjectMapper());

    @Test
    void createsExpectedParserForEachSourceType() {
        assertInstanceOf(CsvImportParser.class, factory.create(ImportSourceType.csv));
        assertInstanceOf(CsvImportParser.class, factory.create(ImportSourceType.tsv));
        assertInstanceOf(TxtImportParser.class, factory.create(ImportSourceType.txt));
        assertInstanceOf(ApkgImportParser.class, factory.create(ImportSourceType.apkg));
        assertInstanceOf(MnemaPackageImportParser.class, factory.create(ImportSourceType.mnema));
        assertInstanceOf(MnpkgImportParser.class, factory.create(ImportSourceType.mnpkg));
    }
}
