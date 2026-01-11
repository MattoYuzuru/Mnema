package app.mnema.importer.client.core;

import com.fasterxml.jackson.databind.JsonNode;

public record CoreCreateCardRequest(
        JsonNode content,
        Integer orderIndex,
        String[] tags,
        String personalNote,
        JsonNode contentOverride,
        String checksum
) {
}
