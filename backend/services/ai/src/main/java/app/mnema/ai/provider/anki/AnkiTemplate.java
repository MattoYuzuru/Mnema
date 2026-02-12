package app.mnema.ai.provider.anki;

public record AnkiTemplate(
        String frontTemplate,
        String backTemplate,
        String css,
        String modelId,
        String modelName,
        String templateName
) {
}
