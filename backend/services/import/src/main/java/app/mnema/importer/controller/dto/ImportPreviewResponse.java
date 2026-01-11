package app.mnema.importer.controller.dto;

import java.util.List;
import java.util.Map;

public record ImportPreviewResponse(
        List<ImportFieldInfo> sourceFields,
        List<ImportFieldInfo> targetFields,
        Map<String, String> suggestedMapping,
        List<Map<String, String>> sample
) {
}
