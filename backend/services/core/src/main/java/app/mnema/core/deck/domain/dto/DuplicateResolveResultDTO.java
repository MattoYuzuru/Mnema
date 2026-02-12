package app.mnema.core.deck.domain.dto;

public record DuplicateResolveResultDTO(
        int groupsProcessed,
        int deletedCards,
        int keptCards,
        boolean globalApplied
) {
}
