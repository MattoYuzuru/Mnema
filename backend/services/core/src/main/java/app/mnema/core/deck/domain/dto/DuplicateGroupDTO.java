package app.mnema.core.deck.domain.dto;

import java.util.List;

public record DuplicateGroupDTO(
        int size,
        List<UserCardDTO> cards
) {
}
