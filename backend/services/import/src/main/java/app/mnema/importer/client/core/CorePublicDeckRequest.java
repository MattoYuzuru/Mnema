package app.mnema.importer.client.core;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorePublicDeckRequest(
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
        UUID forkedFromDeck
) {
}
