package app.mnema.core.deck.adapter;

import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.repository.UserDeckRepository;
import app.mnema.core.review.api.DeckAlgorithmConfig;
import app.mnema.core.review.api.DeckAlgorithmPort;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DeckAlgorithmAdapter implements DeckAlgorithmPort {

    private final UserDeckRepository userDeckRepository;

    public DeckAlgorithmAdapter(UserDeckRepository userDeckRepository) {
        this.userDeckRepository = userDeckRepository;
    }

    @Override
    public DeckAlgorithmConfig getDeckAlgorithm(UUID userId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(userId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        String algo = (deck.getAlgorithmId() == null || deck.getAlgorithmId().isBlank())
                ? "fsrs_v6"
                : deck.getAlgorithmId();

        return new DeckAlgorithmConfig(algo, deck.getAlgorithmParams());
    }

    @Override
    public DeckAlgorithmConfig updateDeckAlgorithm(UUID userId,
                                                   UUID userDeckId,
                                                   String algorithmId,
                                                   JsonNode algorithmParams) {
        for (int attempt = 0; attempt < 3; attempt++) {
            UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                    .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

            if (!deck.getUserId().equals(userId)) {
                throw new SecurityException("Access denied to deck " + userDeckId);
            }

            deck.setAlgorithmId(algorithmId);
            deck.setAlgorithmParams(algorithmParams);
            try {
                userDeckRepository.save(deck);
                return new DeckAlgorithmConfig(algorithmId, algorithmParams);
            } catch (OptimisticLockingFailureException ex) {
                if (attempt == 2) {
                    throw ex;
                }
                Thread.yield();
            }
        }
        throw new IllegalStateException("Failed to update deck algorithm: " + userDeckId);
    }
}
