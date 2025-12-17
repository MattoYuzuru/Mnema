package app.mnema.core.review.service;

import app.mnema.core.review.api.DeckAlgorithmConfig;
import app.mnema.core.review.algorithm.CanonicalProgress;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.api.CardViewPort;
import app.mnema.core.review.api.DeckAlgorithmPort;
import app.mnema.core.review.controller.dto.ReviewAnswerResponse;
import app.mnema.core.review.controller.dto.ReviewNextCardResponse;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.entity.ReviewUserCardEntity;
import app.mnema.core.review.entity.SrCardStateEntity;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import app.mnema.core.review.repository.SrAlgorithmRepository;
import app.mnema.core.review.repository.SrCardStateRepository;
import app.mnema.core.review.util.JsonConfigMerger;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ReviewService {

    private final ReviewUserCardRepository userCardRepo;
    private final SrCardStateRepository stateRepo;
    private final SrAlgorithmRepository algorithmRepo;
    private final app.mnema.core.review.algorithm.AlgorithmRegistry registry;
    private final CardViewPort cardViewPort;
    private final DeckAlgorithmPort deckAlgorithmPort;
    private final JsonConfigMerger configMerger;

    public ReviewService(ReviewUserCardRepository userCardRepo,
                         SrCardStateRepository stateRepo,
                         SrAlgorithmRepository algorithmRepo,
                         app.mnema.core.review.algorithm.AlgorithmRegistry registry,
                         CardViewPort cardViewPort,
                         DeckAlgorithmPort deckAlgorithmPort,
                         JsonConfigMerger configMerger) {
        this.userCardRepo = userCardRepo;
        this.stateRepo = stateRepo;
        this.algorithmRepo = algorithmRepo;
        this.registry = registry;
        this.cardViewPort = cardViewPort;
        this.deckAlgorithmPort = deckAlgorithmPort;
        this.configMerger = configMerger;
    }

    @Transactional(readOnly = true)
    public ReviewNextCardResponse nextCard(UUID userId, UUID userDeckId) {
        return nextCardInternal(userId, userDeckId, Instant.now());
    }

    @Transactional
    public ReviewAnswerResponse answer(UUID userId, UUID userDeckId, UUID userCardId, Rating rating) {
        Instant now = Instant.now();

        ReviewUserCardEntity owned = userCardRepo.findByUserCardIdAndUserId(userCardId, userId)
                .orElseThrow(() -> new SecurityException("Access denied to card " + userCardId));

        if (!userDeckId.equals(owned.getUserDeckId())) {
            throw new SecurityException("Card " + userCardId + " is not in deck " + userDeckId);
        }
        if (owned.isDeleted()) {
            throw new IllegalStateException("Card is deleted: " + userCardId);
        }

        EffectiveAlgo eff = effectiveAlgo(userId, userDeckId);
        SrsAlgorithm algo = registry.require(eff.algorithmId);

        SrCardStateEntity state = stateRepo.findByIdForUpdate(userCardId)
                .orElseGet(() -> {
                    SrCardStateEntity s = new SrCardStateEntity();
                    s.setUserCardId(userCardId);
                    s.setAlgorithmId(eff.algorithmId);
                    s.setState(algo.initialState(eff.effectiveConfig));
                    s.setReviewCount(0);
                    s.setSuspended(false);
                    return s;
                });

        if (state.isSuspended()) {
            throw new IllegalStateException("Card is suspended: " + userCardId);
        }

        if (!eff.algorithmId.equals(state.getAlgorithmId())) {
            migrateState(state, eff, now);
        }

        SrsAlgorithm.ReviewInput input = new SrsAlgorithm.ReviewInput(
                state.getState(),
                state.getLastReviewAt(),
                state.getReviewCount()
        );

        SrsAlgorithm.ReviewComputation result = algo.apply(input, rating, now, eff.effectiveConfig);

        state.setAlgorithmId(eff.algorithmId);
        state.setState(result.newState());
        state.setLastReviewAt(result.lastReviewAt());
        state.setNextReviewAt(result.nextReviewAt());
        state.setReviewCount(state.getReviewCount() + result.reviewCountDelta());

        stateRepo.save(state);

        ReviewNextCardResponse next = nextCardInternal(userId, userDeckId, now);

        return new ReviewAnswerResponse(
                userCardId,
                rating,
                state.getNextReviewAt(),
                next
        );
    }

    private ReviewNextCardResponse nextCardInternal(UUID userId, UUID userDeckId, Instant now) {
        EffectiveAlgo eff = effectiveAlgo(userId, userDeckId);
        SrsAlgorithm algo = registry.require(eff.algorithmId);

        List<UUID> due = userCardRepo.findDueCardIds(userId, userDeckId, now, PageRequest.of(0, 1));
        List<UUID> ids = !due.isEmpty()
                ? due
                : userCardRepo.findNewCardIds(userId, userDeckId, PageRequest.of(0, 1));

        if (ids.isEmpty()) {
            return null;
        }

        UUID userCardId = ids.getFirst();
        CardViewPort.CardView view = cardViewPort.getCardViews(userId, ids).getFirst();

        SrCardStateEntity state = stateRepo.findById(userCardId).orElse(null);

        SrsAlgorithm.ReviewInput input;
        String stateAlgorithmId = eff.algorithmId;

        Instant dueAt = null;
        boolean isDue = false;

        if (state == null) {
            input = new SrsAlgorithm.ReviewInput(algo.initialState(eff.effectiveConfig), null, 0);
        } else {
            dueAt = state.getNextReviewAt();
            isDue = (dueAt != null && !dueAt.isAfter(now));

            if (eff.algorithmId.equals(state.getAlgorithmId())) {
                input = new SrsAlgorithm.ReviewInput(state.getState(), state.getLastReviewAt(), state.getReviewCount());
            } else {
                input = new SrsAlgorithm.ReviewInput(migrateJsonForPreview(state, eff), state.getLastReviewAt(), state.getReviewCount());
            }
        }

        Map<Rating, String> intervals = new EnumMap<>(Rating.class);
        Map<Rating, Instant> preview = algo.previewNextReviewAt(input, now, eff.effectiveConfig);
        for (Rating r : Rating.values()) {
            intervals.put(r, humanize(Duration.between(now, preview.get(r))));
        }

        return new ReviewNextCardResponse(
                userDeckId,
                eff.algorithmId,
                view.userCardId(),
                view.publicCardId(),
                view.isCustom(),
                view.effectiveContent(),
                intervals,
                dueAt,
                isDue
        );
    }

    private EffectiveAlgo effectiveAlgo(UUID userId, UUID userDeckId) {
        DeckAlgorithmConfig deck = deckAlgorithmPort.getDeckAlgorithm(userId, userDeckId);

        String algorithmId = (deck.algorithmId() == null || deck.algorithmId().isBlank()) ? "sm2" : deck.algorithmId();

        var algoEntity = algorithmRepo.findById(algorithmId)
                .orElseThrow(() -> new IllegalArgumentException("Algorithm not found in DB: " + algorithmId));

        JsonNode effective = configMerger.merge(algoEntity.getDefaultConfig(), deck.algorithmParams());

        return new EffectiveAlgo(algorithmId, effective);
    }

    private void migrateState(SrCardStateEntity state, EffectiveAlgo eff, Instant now) {
        SrsAlgorithm toAlgo = registry.require(eff.algorithmId);

        SrsAlgorithm fromAlgo;
        try {
            fromAlgo = registry.require(state.getAlgorithmId());
        } catch (Exception ignored) {
            fromAlgo = null;
        }

        JsonNode newState;
        if (fromAlgo == null) {
            newState = toAlgo.initialState(eff.effectiveConfig);
        } else {
            CanonicalProgress p = fromAlgo.toCanonical(state.getState());
            newState = toAlgo.fromCanonical(p, eff.effectiveConfig);
        }

        state.setAlgorithmId(eff.algorithmId);
        state.setState(newState);

        if (state.getNextReviewAt() == null) {
            state.setNextReviewAt(now);
        }
    }

    private JsonNode migrateJsonForPreview(SrCardStateEntity state, EffectiveAlgo eff) {
        SrsAlgorithm toAlgo = registry.require(eff.algorithmId);

        SrsAlgorithm fromAlgo;
        try {
            fromAlgo = registry.require(state.getAlgorithmId());
        } catch (Exception ignored) {
            fromAlgo = null;
        }

        if (fromAlgo == null) {
            return toAlgo.initialState(eff.effectiveConfig);
        }

        CanonicalProgress p = fromAlgo.toCanonical(state.getState());
        return toAlgo.fromCanonical(p, eff.effectiveConfig);
    }

    private static String humanize(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    private record EffectiveAlgo(String algorithmId, JsonNode effectiveConfig) {
    }
}
