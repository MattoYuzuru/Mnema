package app.mnema.core.deck.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record PublicCardDTO(
        UUID cardId,
        int orderIndex,
        String[] tags,
        JsonNode content
) {
}
