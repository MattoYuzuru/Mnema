package app.mnema.importer.client.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreCardTemplateResponse(
        UUID templateId,
        UUID ownerId,
        String name,
        String description,
        boolean isPublic,
        Instant createdAt,
        Instant updatedAt,
        JsonNode layout,
        JsonNode aiProfile,
        String iconUrl,
        List<CoreFieldTemplate> fields
) {
}
