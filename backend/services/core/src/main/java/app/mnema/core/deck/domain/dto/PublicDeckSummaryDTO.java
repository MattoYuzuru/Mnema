package app.mnema.core.deck.domain.dto;

import java.util.UUID;

public record PublicDeckSummaryDTO(
        UUID deckId,
        int version,
        String name,
        String description,
        String language,
        String[] tags,
        boolean isPublic,
        boolean isListed
) {
}
