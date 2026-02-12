package app.mnema.core.deck.domain.request;

import java.util.List;

public record MissingFieldSummaryRequest(
        List<String> fields,
        Integer sampleLimit
) {
}
