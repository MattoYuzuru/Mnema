package app.mnema.core.deck.domain.request;

import java.util.List;

public record DuplicateSearchRequest(
        List<String> fields,
        Integer limitGroups,
        Integer perGroupLimit,
        Boolean includeSemantic,
        Double semanticThreshold
) {
}
