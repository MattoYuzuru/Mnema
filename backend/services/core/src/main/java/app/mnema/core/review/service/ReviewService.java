package app.mnema.core.review.service;

import app.mnema.core.review.algorithm.AlgorithmRegistry;
import app.mnema.core.review.algorithm.CanonicalProgress;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.api.CardViewPort;
import app.mnema.core.review.api.DeckAlgorithmConfig;
import app.mnema.core.review.api.DeckAlgorithmPort;
import app.mnema.core.review.controller.dto.ReviewAnswerResponse;
import app.mnema.core.review.controller.dto.ReviewDeckAlgorithmResponse;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {

    private final ReviewUserCardRepository userCardRepo;
    private final SrCardStateRepository stateRepo;
    private final SrAlgorithmRepository algorithmRepo;
    private final AlgorithmRegistry registry;
    private final CardViewPort cardViewPort;
    private final DeckAlgorithmPort deckAlgorithmPort;
    private final JsonConfigMerger configMerger;
    private final UserDeckPreferencesService preferencesService;

    public ReviewService(ReviewUserCardRepository userCardRepo,
                         SrCardStateRepository stateRepo,
                         SrAlgorithmRepository algorithmRepo,
                         AlgorithmRegistry registry,
                         CardViewPort cardViewPort,
                         DeckAlgorithmPort deckAlgorithmPort,
                         JsonConfigMerger configMerger,
                         UserDeckPreferencesService preferencesService) {
        this.userCardRepo = userCardRepo;
        this.stateRepo = stateRepo;
        this.algorithmRepo = algorithmRepo;
        this.registry = registry;
        this.cardViewPort = cardViewPort;
        this.deckAlgorithmPort = deckAlgorithmPort;
        this.configMerger = configMerger;
        this.preferencesService = preferencesService;
    }

    @Transactional(readOnly = true)
    public ReviewDeckAlgorithmResponse getDeckAlgorithm(UUID userId, UUID userDeckId) {
        AlgorithmContext ctx = resolveAlgorithmContext(userId, userDeckId);
        AlgorithmStats stats = computeAlgorithmStats(userId, userDeckId, ctx.algorithmId());
        return buildAlgorithmResponse(userDeckId, ctx, stats);
    }

    @Transactional
    public ReviewDeckAlgorithmResponse updateDeckAlgorithm(UUID userId,
                                                           UUID userDeckId,
                                                           String algorithmId,
                                                           JsonNode algorithmParams) {
        if (algorithmId == null || algorithmId.isBlank()) {
            throw new IllegalArgumentException("Algorithm id is required");
        }

        registry.require(algorithmId);
        DeckAlgorithmConfig updated = deckAlgorithmPort.updateDeckAlgorithm(userId, userDeckId, algorithmId, algorithmParams);
        AlgorithmContext ctx = buildAlgorithmContext(updated.algorithmId(), updated.algorithmParams());
        AlgorithmStats stats = computeAlgorithmStats(userId, userDeckId, ctx.algorithmId());
        return buildAlgorithmResponse(userDeckId, ctx, stats);
    }

    @Transactional
    public ReviewAnswerResponse answer(UUID userId,
                                       UUID userDeckId,
                                       UUID userCardId,
                                       Rating rating) {
        Instant now = Instant.now();
        AlgorithmContext algorithmContext = resolveAlgorithmContext(userId, userDeckId);

        ReviewUserCardEntity card = userCardRepo.findByUserCardIdAndUserId(userCardId, userId)
                .orElseThrow(() -> new IllegalArgumentException("User card not found: " + userCardId));

        if (!userDeckId.equals(card.getUserDeckId())) {
            throw new SecurityException("Access denied to card " + userCardId);
        }
        if (card.isDeleted()) {
            throw new IllegalStateException("Card is deleted: " + userCardId);
        }

        SrCardStateEntity current = stateRepo.findByIdForUpdate(userCardId).orElse(null);
        SrsAlgorithm.ReviewInput input = buildReviewInput(current, algorithmContext);

        SrsAlgorithm.ReviewComputation computation = algorithmContext.algorithm()
                .apply(input, rating, now, algorithmContext.effectiveConfig());

        SrCardStateEntity nextState = (current == null) ? new SrCardStateEntity() : current;
        if (nextState.getUserCardId() == null) {
            nextState.setUserCardId(userCardId);
        }
        nextState.setAlgorithmId(algorithmContext.algorithmId());
        nextState.setState(computation.newState());
        nextState.setLastReviewAt(computation.lastReviewAt() == null ? now : computation.lastReviewAt());
        nextState.setNextReviewAt(computation.nextReviewAt());
        int newCount = Math.max(0, input.reviewCount() + computation.reviewCountDelta());
        nextState.setReviewCount(newCount);
        nextState.setSuspended(false);

        stateRepo.save(nextState);
        preferencesService.incrementCounters(userDeckId, current == null, now);

        ReviewNextCardResponse next = nextCard(userId, userDeckId);
        return new ReviewAnswerResponse(
                userCardId,
                rating,
                computation.nextReviewAt(),
                next
        );
    }

    @Transactional
    public ReviewNextCardResponse nextCard(UUID userId, UUID userDeckId) {
        Instant now = Instant.now();
        var preferences = preferencesService.getSnapshot(userDeckId, now);
        AlgorithmContext algorithmContext = resolveAlgorithmContext(userId, userDeckId);

        Instant dueHorizon = now.plus(preferences.learningHorizon());
        long dueNowCount = userCardRepo.countDue(userId, userDeckId, now);
        long dueHorizonCount = userCardRepo.countDue(userId, userDeckId, dueHorizon);
        long dueSoonCount = Math.max(0, dueHorizonCount - dueNowCount);
        long newCount = userCardRepo.countNew(userId, userDeckId);
        long remainingNewQuota = preferences.remainingNewQuota();
        long availableNew = newCount;
        if (remainingNewQuota != Long.MAX_VALUE) {
            availableNew = Math.min(availableNew, remainingNewQuota);
        }

        var queue = new ReviewNextCardResponse.QueueSummary(
                dueNowCount,
                availableNew,
                dueNowCount + availableNew + dueSoonCount
        );

        // 1) due, 2) new
        UUID nextCardId = null;
        boolean due = false;
        boolean learningAhead = false;

        var dueIds = userCardRepo.findDueCardIds(userId, userDeckId, now, PageRequest.of(0, 1));
        if (!dueIds.isEmpty()) {
            nextCardId = dueIds.getFirst();
            due = true;
        } else {
            if (availableNew > 0) {
                var newIds = userCardRepo.findNewCardIds(userId, userDeckId, PageRequest.of(0, 1));
                if (!newIds.isEmpty()) {
                    nextCardId = newIds.getFirst();
                }
            }
            if (nextCardId == null) {
                var learningIds = userCardRepo.findDueCardIds(userId, userDeckId, dueHorizon, PageRequest.of(0, 1));
                if (!learningIds.isEmpty()) {
                    nextCardId = learningIds.getFirst();
                    learningAhead = true;
                }
            }
        }

        if (nextCardId == null) {
            return new ReviewNextCardResponse(
                    userDeckId,
                    algorithmContext.algorithmId(),
                    null,
                    null,
                    false,
                    null,
                    Map.of(),
                    null,
                    false,
                    queue
            );
        }

        // Контент карточки (public/custom + override)
        var views = cardViewPort.getCardViews(userId, List.of(nextCardId));
        if (views.isEmpty()) {
            throw new IllegalStateException("CardViewPort returned empty for userCardId=" + nextCardId);
        }
        var view = views.getFirst();

        // Состояние SRS (может отсутствовать для new-card)
        var stateOpt = stateRepo.findById(nextCardId);
        SrsAlgorithm.ReviewInput input = buildReviewInput(stateOpt.orElse(null), algorithmContext);
        Instant dueAt = stateOpt.map(SrCardStateEntity::getNextReviewAt).orElse(null);

        // Preview интервалов под 4 кнопки
        Map<Rating, Instant> nextAt = algorithmContext.algorithm()
                .previewNextReviewAt(input, now, algorithmContext.effectiveConfig());
        Map<Rating, ReviewNextCardResponse.IntervalPreview> intervals = toIntervalPreview(nextAt, now);

        return new ReviewNextCardResponse(
                userDeckId,
                algorithmContext.algorithmId(),
                view.userCardId(),
                view.publicCardId(),
                view.isCustom(),
                view.effectiveContent(),
                intervals,
                dueAt,
                due && !learningAhead,
                queue
        );
    }

    private AlgorithmContext resolveAlgorithmContext(UUID userId, UUID userDeckId) {
        DeckAlgorithmConfig deckAlgo = deckAlgorithmPort.getDeckAlgorithm(userId, userDeckId);
        return buildAlgorithmContext(deckAlgo.algorithmId(), deckAlgo.algorithmParams());
    }

    private AlgorithmContext buildAlgorithmContext(String algorithmId, JsonNode deckConfig) {
        SrsAlgorithm algorithm = registry.require(algorithmId);
        JsonNode defaultConfig = algorithmRepo.findById(algorithmId)
                .map(app.mnema.core.review.entity.SrAlgorithmEntity::getDefaultConfig)
                .orElse(null);

        JsonNode effective = configMerger.merge(defaultConfig, deckConfig);
        return new AlgorithmContext(algorithmId, algorithm, effective, deckConfig);
    }

    private SrsAlgorithm.ReviewInput buildReviewInput(SrCardStateEntity state, AlgorithmContext ctx) {
        if (state == null) {
            return new SrsAlgorithm.ReviewInput(
                    ctx.algorithm().initialState(ctx.effectiveConfig()),
                    null,
                    0
            );
        }

        JsonNode effectiveState = state.getState();
        if (state.getAlgorithmId() != null && !state.getAlgorithmId().equals(ctx.algorithmId())) {
            SrsAlgorithm previous = registry.require(state.getAlgorithmId());
            CanonicalProgress progress = previous.toCanonical(state.getState());
            effectiveState = ctx.algorithm().fromCanonical(progress, ctx.effectiveConfig());
        }

        return new SrsAlgorithm.ReviewInput(
                effectiveState,
                state.getLastReviewAt(),
                state.getReviewCount()
        );
    }

    private static Map<Rating, ReviewNextCardResponse.IntervalPreview> toIntervalPreview(Map<Rating, Instant> nextAt,
                                                                                         Instant now) {
        var out = new EnumMap<Rating, ReviewNextCardResponse.IntervalPreview>(Rating.class);
        for (var e : nextAt.entrySet()) {
            Instant at = e.getValue();
            String display = at == null ? null : humanize(Duration.between(now, at));
            out.put(e.getKey(), new ReviewNextCardResponse.IntervalPreview(at, display));
        }
        return out;
    }

    private static String humanize(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    private ReviewDeckAlgorithmResponse buildAlgorithmResponse(UUID userDeckId,
                                                               AlgorithmContext ctx,
                                                               AlgorithmStats stats) {
        return new ReviewDeckAlgorithmResponse(
                userDeckId,
                ctx.algorithmId(),
                ctx.deckConfig(),
                ctx.effectiveConfig(),
                stats.activeCards(),
                stats.trackedCards(),
                stats.pendingMigrationCards()
        );
    }

    private AlgorithmStats computeAlgorithmStats(UUID userId, UUID userDeckId, String algorithmId) {
        long active = userCardRepo.countActive(userId, userDeckId);
        long tracked = stateRepo.countTrackedCards(userId, userDeckId);
        long pending = stateRepo.countPendingMigration(userId, userDeckId, algorithmId);
        return new AlgorithmStats(active, tracked, pending);
    }

    private record AlgorithmContext(String algorithmId,
                                    SrsAlgorithm algorithm,
                                    JsonNode effectiveConfig,
                                    JsonNode deckConfig) {
    }

    private record AlgorithmStats(long activeCards, long trackedCards, long pendingMigrationCards) {
    }
}
