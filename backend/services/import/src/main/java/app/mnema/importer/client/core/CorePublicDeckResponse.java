package app.mnema.importer.client.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CorePublicDeckResponse(
        UUID deckId,
        Integer version,
        UUID authorId,
        String name,
        String description,
        UUID iconMediaId,
        UUID templateId,
        boolean isPublic,
        boolean isListed,
        String language,
        String[] tags,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        UUID forkedFromDeck
) {
}
