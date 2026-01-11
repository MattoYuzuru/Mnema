package app.mnema.importer.client.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreUserCardResponse(
        UUID userCardId,
        UUID publicCardId,
        boolean isCustom,
        boolean isDeleted,
        String personalNote,
        JsonNode effectiveContent
) {
}
