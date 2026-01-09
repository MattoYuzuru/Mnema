package app.mnema.importer.client.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreFieldTemplate(
        UUID fieldId,
        UUID templateId,
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
