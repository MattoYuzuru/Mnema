package app.mnema.importer.service.parser;

import java.util.List;

public record ImportPreview(
        List<String> fields,
        List<ImportRecord> sample
) {
}
