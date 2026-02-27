package app.mnema.core.deck.domain.dto;

import java.util.List;

public record DuplicateGroupDTO(
        String matchType,
        Double confidence,
        int size,
        List<UserCardDTO> cards
) {
}
