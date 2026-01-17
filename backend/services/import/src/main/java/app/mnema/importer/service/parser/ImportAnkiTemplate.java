package app.mnema.importer.service.parser;

public record ImportAnkiTemplate(
        String modelId,
        String modelName,
        String templateName,
        String frontTemplate,
        String backTemplate,
        String css
) {
}
