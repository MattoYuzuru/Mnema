package app.mnema.ai.provider.anki;

public record AnkiRendered(
        String frontHtml,
        String backHtml,
        String css,
        String modelId,
        String modelName,
        String templateName
) {
}
