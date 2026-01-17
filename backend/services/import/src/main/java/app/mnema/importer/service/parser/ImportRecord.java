package app.mnema.importer.service.parser;

import java.util.Map;

public record ImportRecord(
        Map<String, String> fields,
        ImportRecordProgress progress,
        ImportAnkiTemplate ankiTemplate,
        Integer orderIndex
) {
}
