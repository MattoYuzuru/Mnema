package app.mnema.core.deck.adapter;

import app.mnema.core.review.api.DeckAlgorithmConfig;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.repository.UserDeckRepository;
import app.mnema.core.review.api.DeckAlgorithmPort;
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
                ? "sm2"
                : deck.getAlgorithmId();

        return new DeckAlgorithmConfig(algo, deck.getAlgorithmParams());
    }
}
