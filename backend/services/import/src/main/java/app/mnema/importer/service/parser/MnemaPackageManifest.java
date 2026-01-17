package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record MnemaPackageManifest(
        String format,
        int version,
        DeckMeta deck,
        TemplateMeta template
) {

    public record DeckMeta(
            String name,
            String description,
            String language,
            String[] tags
    ) {
    }

    public record TemplateMeta(
            String name,
            String description,
            JsonNode layout,
            boolean anki,
            List<FieldMeta> fields
    ) {
    }

    public record FieldMeta(
            String name,
            String label,
            String fieldType,
            boolean isRequired,
            boolean isOnFront,
            Integer orderIndex,
            String defaultValue,
            String helpText
    ) {
    }
}
