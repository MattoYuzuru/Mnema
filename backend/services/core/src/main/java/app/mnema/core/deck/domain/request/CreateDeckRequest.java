package app.mnema.core.deck.domain.request;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateDeckRequest(
        String displayName,
        String displayDescription,
        String algorithmId,
        JsonNode algorithmParams
) {
}
