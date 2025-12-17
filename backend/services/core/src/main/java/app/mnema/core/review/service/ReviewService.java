package app.mnema.core.review.service;

import app.mnema.core.review.algorithm.AlgorithmRegistry;
import app.mnema.core.review.api.CardViewPort;
import app.mnema.core.review.api.DeckAlgorithmPort;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.entity.SrCardStateEntity;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import app.mnema.core.review.repository.SrCardStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReviewService {

    private final ReviewUserCardRepository userCardRepo;
    private final SrCardStateRepository stateRepo;
    private final AlgorithmRegistry registry;
    private final CardViewPort cardViewPort;
    private final DeckAlgorithmPort deckAlgorithmPort;

    public ReviewService(ReviewUserCardRepository userCardRepo,
                         SrCardStateRepository stateRepo,
                         AlgorithmRegistry registry,
                         CardViewPort cardViewPort,
                         DeckAlgorithmPort deckAlgorithmPort) {
        this.userCardRepo = userCardRepo;
        this.stateRepo = stateRepo;
        this.registry = registry;
        this.cardViewPort = cardViewPort;
        this.deckAlgorithmPort = deckAlgorithmPort;
    }

    @Transactional(readOnly = true)
    public List<CardViewPort.CardView> next(UUID userId, UUID userDeckId, int limit) {
        Instant now = Instant.now();

        var pageable = PageRequest.of(0, Math.max(1, Math.min(limit, 50)));

        List<UUID> ids = userCardRepo.findDueCardIds(userId, userDeckId, now, pageable);
        if (ids.isEmpty()) {
            ids = userCardRepo.findNewCardIds(userId, userDeckId, pageable);
        }
        if (ids.isEmpty()) return List.of();

        return cardViewPort.getCardViews(userId, ids);
    }

    @Transactional
    public void answer(UUID userId, UUID userCardId, Rating rating) {
        Instant now = Instant.now();

        SrCardStateEntity state = stateRepo.findByIdForUpdate(userCardId)
                .orElseGet(() -> {
                    var s = new SrCardStateEntity();
                    s.setUserCardId(userCardId);
                    s.setAlgorithmId("sm2"); // дефолт на старте, лучше привязать к user_deck.algorithm_id позже
                    s.setState(registry.require("sm2").initialState());
                    s.setReviewCount(0);
                    s.setSuspended(false);
                    return s;
                });

        if (state.isSuspended()) {
            throw new IllegalStateException("Card is suspended");
        }

        var algo = registry.require(state.getAlgorithmId());
        var result = algo.apply(state.getState(), rating, now);

        state.setState(result.newState());
        state.setLastReviewAt(result.lastReviewAt());
        state.setNextReviewAt(result.nextReviewAt());
        state.setReviewCount(state.getReviewCount() + result.reviewCountDelta());

        stateRepo.save(state);
    }

    @Transactional
    public void switchCardAlgorithm(UUID userCardId, String newAlgorithmId) {
        var state = stateRepo.findByIdForUpdate(userCardId)
                .orElseThrow(() -> new IllegalArgumentException("State not found for card " + userCardId));

        var fromAlgo = registry.require(state.getAlgorithmId());
        var toAlgo = registry.require(newAlgorithmId);

        var canon = fromAlgo.toCanonical(state.getState());
        var newState = toAlgo.fromCanonical(canon);

        state.setAlgorithmId(newAlgorithmId);
        state.setState(newState);

        Instant now = Instant.now();
        Instant next = state.getNextReviewAt();
        if (next == null) state.setNextReviewAt(now);
        else if (next.isBefore(now)) state.setNextReviewAt(now);

        stateRepo.save(state);
    }
}
