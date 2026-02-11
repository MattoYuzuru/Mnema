package app.mnema.core.deck.domain.request;

import java.util.List;

public record DuplicateResolveRequest(
        List<String> fields,
        String scope,
        java.util.UUID operationId
) {
}
