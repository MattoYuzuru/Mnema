package app.mnema.core.deck.domain.dto;

import java.time.Instant;
import java.util.UUID;

public record UserDeckDTO(
        UUID userDeckId,
        UUID publicDeckId,
        Integer subscribedVersion,
        Integer currentVersion,
        String displayName,
        String displayDescription,
        String algorithmId,
        boolean autoUpdate,
        boolean archived,
        Instant lastSyncedAt
) {
}
