package app.mnema.core.review.api;

import java.util.UUID;

public interface DeckAlgorithmPort {
    DeckAlgorithmConfig getDeckAlgorithm(UUID userId, UUID userDeckId);
}