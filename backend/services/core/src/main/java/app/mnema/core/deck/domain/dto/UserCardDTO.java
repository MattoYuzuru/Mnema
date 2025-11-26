package app.mnema.core.deck.domain.dto;


import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record UserCardDTO(
        UUID userCardId,
        UUID publicCardId,
        boolean isCustom,
        boolean isDeleted,
        boolean isSuspended,
        String personalNote,
        JsonNode effectiveContent,
        Instant lastReviewAt,
        Instant nextReviewAt,
        int reviewCount
) {
}
