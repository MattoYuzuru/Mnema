package app.mnema.importer.client.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record CoreCardTemplateRequest(
        UUID templateId,
        UUID ownerId,
        String name,
        String description,
        boolean isPublic,
        JsonNode layout,
        JsonNode aiProfile,
        String iconUrl,
        List<CoreFieldTemplate> fields
) {
}
