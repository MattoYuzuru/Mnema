package app.mnema.core.deck.domain.dto;

import java.util.UUID;

public record DeckSizeDTO(
        UUID deckId,
        long cardsQty
) {
}
