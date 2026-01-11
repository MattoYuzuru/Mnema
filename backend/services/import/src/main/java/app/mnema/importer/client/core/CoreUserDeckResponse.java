package app.mnema.importer.client.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreUserDeckResponse(
        UUID userDeckId,
        UUID userId,
        UUID publicDeckId,
        Integer subscribedVersion,
        Integer currentVersion,
        boolean autoUpdate,
        String algorithmId,
        JsonNode algorithmParams,
        String displayName,
        String displayDescription,
        Instant createdAt,
        Instant lastSyncedAt,
        boolean archived
) {
}
