package app.mnema.core.deck.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record UserDeckDTO(
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
