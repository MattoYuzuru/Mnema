package app.mnema.core.deck.domain.request;

import java.util.List;

public record MissingFieldCardsRequest(
        List<String> fields,
        Integer limit
) {
}
