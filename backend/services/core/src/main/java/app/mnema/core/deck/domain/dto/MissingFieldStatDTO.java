package app.mnema.core.deck.domain.dto;

import java.util.List;

public record MissingFieldStatDTO(
        String field,
        long missingCount,
        List<UserCardDTO> sampleCards
) {
}
