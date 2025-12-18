package app.mnema.core.review.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public interface DeckAlgorithmPort {
    DeckAlgorithmConfig getDeckAlgorithm(UUID userId, UUID userDeckId);

    DeckAlgorithmConfig updateDeckAlgorithm(UUID userId,
                                            UUID userDeckId,
                                            String algorithmId,
                                            JsonNode algorithmParams);
}
