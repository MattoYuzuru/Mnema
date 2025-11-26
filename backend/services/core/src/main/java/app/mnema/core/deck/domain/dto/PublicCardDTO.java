package app.mnema.core.deck.domain.dto;

import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record PublicCardDTO(
        UUID deckId,
        Integer deckVersion,
        UUID cardId,
        JsonNode content,
        Integer orderIndex,
        String[] tags,
        Instant createdAt,
        Instant updatedAt,
        boolean active,
        String checksum
) {
}
