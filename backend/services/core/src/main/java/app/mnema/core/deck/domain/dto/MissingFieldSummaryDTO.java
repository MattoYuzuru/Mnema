package app.mnema.core.deck.domain.dto;

import java.util.List;

public record MissingFieldSummaryDTO(
        List<MissingFieldStatDTO> fields,
        int sampleLimit
) {
}
