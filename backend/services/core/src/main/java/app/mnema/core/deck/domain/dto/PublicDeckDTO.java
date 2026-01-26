package app.mnema.core.deck.domain.dto;

import app.mnema.core.deck.domain.type.LanguageTag;

import java.time.Instant;
import java.util.UUID;

public record PublicDeckDTO(
        UUID deckId,
        Integer version,
        UUID authorId,
        String name,
        String description,
        UUID iconMediaId,
        String iconUrl,
        UUID templateId,
        Integer templateVersion,
        boolean isPublic,
        boolean isListed,
        LanguageTag language,
        String[] tags,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        UUID forkedFromDeck
) {
}
