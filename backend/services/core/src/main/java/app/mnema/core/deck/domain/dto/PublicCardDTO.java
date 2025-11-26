package app.mnema.core.deck.domain.dto;

import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record PublicCardDTO(
        Integer deckVersion,
        UUID cardId,
        PublicDeckEntity deck,
        JsonNode content,
        int orderIndex,
        String[] tags,
        Instant createdAt,
        Instant updatedAt,
        boolean active,
        String checksum
) {
}
