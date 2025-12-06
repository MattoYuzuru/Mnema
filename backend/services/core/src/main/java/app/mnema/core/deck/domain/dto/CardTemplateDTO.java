package app.mnema.core.deck.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CardTemplateDTO(
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
        List<FieldTemplateDTO> fields
) {
}
