package app.mnema.core.review.service;

import app.mnema.core.review.algorithm.AlgorithmRegistry;
import app.mnema.core.review.algorithm.CanonicalProgress;
import app.mnema.core.review.algorithm.ReviewContext;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.api.CardViewPort;
import app.mnema.core.review.api.DeckAlgorithmConfig;
import app.mnema.core.review.api.DeckAlgorithmPort;
import app.mnema.core.review.controller.dto.*;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.domain.ReviewSource;
import app.mnema.core.review.entity.ReviewUserCardEntity;
import app.mnema.core.review.entity.SrCardStateEntity;
import app.mnema.core.review.entity.SrReviewLogEntity;
import app.mnema.core.review.repository.ReviewDayCompletionRepository;
import app.mnema.core.review.repository.ReviewStatsRepository;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import app.mnema.core.review.repository.SrReviewLogRepository;
import app.mnema.core.review.repository.SrCardStateRepository;
import app.mnema.core.review.util.JsonConfigMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ReviewService {
    private static final int DEFAULT_SESSION_GAP_MINUTES = 30;

    private final ReviewUserCardRepository userCardRepo;
    private final SrCardStateRepository stateRepo;
    private final AlgorithmRegistry registry;
    private final CardViewPort cardViewPort;
    private final DeckAlgorithmPort deckAlgorithmPort;
    private final JsonConfigMerger configMerger;
    private final UserDeckPreferencesService preferencesService;
    private final SrReviewLogRepository reviewLogRepository;
    private final ReviewStatsRepository reviewStatsRepository;
    private final ReviewDayCompletionRepository reviewDayCompletionRepository;
    private final AlgorithmDefaultConfigCache defaultConfigCache;
    private final DeckAlgorithmUpdateBuffer updateBuffer;

    public ReviewService(ReviewUserCardRepository userCardRepo,
                         SrCardStateRepository stateRepo,
                         AlgorithmRegistry registry,
                         CardViewPort cardViewPort,
                         DeckAlgorithmPort deckAlgorithmPort,
                         JsonConfigMerger configMerger,
                         UserDeckPreferencesService preferencesService,
                         SrReviewLogRepository reviewLogRepository,
                         ReviewStatsRepository reviewStatsRepository,
                         ReviewDayCompletionRepository reviewDayCompletionRepository,
                         AlgorithmDefaultConfigCache defaultConfigCache,
                         DeckAlgorithmUpdateBuffer updateBuffer) {
        this.userCardRepo = userCardRepo;
        this.stateRepo = stateRepo;
        this.registry = registry;
        this.cardViewPort = cardViewPort;
        this.deckAlgorithmPort = deckAlgorithmPort;
        this.configMerger = configMerger;
        this.preferencesService = preferencesService;
        this.reviewLogRepository = reviewLogRepository;
        this.reviewStatsRepository = reviewStatsRepository;
        this.reviewDayCompletionRepository = reviewDayCompletionRepository;
        this.defaultConfigCache = defaultConfigCache;
        this.updateBuffer = updateBuffer;
    }

    @Transactional(readOnly = true)
    public ReviewSummaryResponse summary(UUID userId) {
        List<UUID> deckIds = userCardRepo.findActiveDeckIds(userId);
        if (deckIds.isEmpty()) {
            return new ReviewSummaryResponse(0, 0);
        }

        Instant now = Instant.now();
        Map<UUID, Long> newByDeck = toCountMap(userCardRepo.countNewByDeck(userId, deckIds));

        long totalDue = 0;
        long totalNew = 0;
        for (UUID deckId : deckIds) {
            UserDeckPreferencesService.PreferencesSnapshot preferences = preferencesService.getSnapshot(deckId, now);
            Instant reviewDayEnd = preferences.reviewDay(now).end();
            long dueCount = userCardRepo.countDue(userId, deckId, reviewDayEnd);
            long newCount = newByDeck.getOrDefault(deckId, 0L);
            long remainingNewQuota = preferences.remainingNewQuota();
            long remainingReviewQuota = preferences.remainingReviewQuota();
            long availableNew = remainingNewQuota == Long.MAX_VALUE
                    ? newCount
                    : Math.min(newCount, remainingNewQuota);
            long availableDue = remainingReviewQuota == Long.MAX_VALUE
                    ? dueCount
                    : Math.min(dueCount, remainingReviewQuota);
            totalDue += availableDue;
            totalNew += availableNew;
        }

        return new ReviewSummaryResponse(totalDue, totalNew);
    }

    private static Map<UUID, Long> toCountMap(List<ReviewUserCardRepository.DeckCount> counts) {
        Map<UUID, Long> result = new HashMap<>();
        for (ReviewUserCardRepository.DeckCount count : counts) {
            result.put(count.getUserDeckId(), count.getCount());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ReviewDeckAlgorithmResponse getDeckAlgorithm(UUID userId, UUID userDeckId) {
        AlgorithmContext ctx = resolveAlgorithmContext(userId, userDeckId);
        AlgorithmStats stats = computeAlgorithmStats(userId, userDeckId, ctx.algorithmId());
        var preferences = preferencesService.getSnapshot(userDeckId, Instant.now());
        return buildAlgorithmResponse(userDeckId, ctx, stats, preferences);
    }

    @Transactional
    public ReviewDeckAlgorithmResponse updateDeckAlgorithm(UUID userId,
                                                           UUID userDeckId,
                                                           String algorithmId,
                                                           JsonNode algorithmParams,
                                                           ReviewPreferencesDto reviewPreferences) {
        if (algorithmId == null || algorithmId.isBlank()) {
            throw new IllegalArgumentException("Algorithm id is required");
        }

        registry.require(algorithmId);
        updateBuffer.clear(userDeckId);
        DeckAlgorithmConfig updated = deckAlgorithmPort.updateDeckAlgorithm(userId, userDeckId, algorithmId, algorithmParams);
        UserDeckPreferencesService.PreferencesSnapshot preferences = reviewPreferences == null
                ? preferencesService.getSnapshot(userDeckId, Instant.now())
                : preferencesService.updatePreferences(
                userDeckId,
                reviewPreferences.dailyNewLimit(),
                reviewPreferences.learningHorizonHours(),
                reviewPreferences.maxReviewPerDay(),
                reviewPreferences.dayCutoffHour(),
                reviewPreferences.timeZone()
        );
        AlgorithmContext ctx = buildAlgorithmContext(updated.algorithmId(), updated.algorithmParams());
        AlgorithmStats stats = computeAlgorithmStats(userId, userDeckId, ctx.algorithmId());
        return buildAlgorithmResponse(userDeckId, ctx, stats, preferences);
    }

    @Transactional
    public ReviewAnswerResponse answer(UUID userId,
                                       UUID userDeckId,
                                       UUID userCardId,
                                       Rating rating,
                                       Integer responseMs,
                                       ReviewSource source,
                                       JsonNode features) {
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
        JsonNode mergedFeatures = buildFeatures(input, rating, responseMs, source, features, now);
        ReviewContext context = new ReviewContext(
                source == null ? ReviewSource.other : source,
                responseMs,
                mergedFeatures
        );

        SrsAlgorithm.ReviewOutcome outcome = algorithmContext.algorithm()
                .review(input, rating, now, algorithmContext.effectiveConfig(), context, algorithmContext.deckConfig());
        SrsAlgorithm.ReviewComputation computation = outcome.computation();

        logReview(userCardId, algorithmContext.algorithmId(), rating, responseMs, context.source(), context.features(),
                input.state(), computation.newState(), now);

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

        JsonNode updatedDeckConfig = outcome.updatedDeckConfig();
        if (updatedDeckConfig != null && !Objects.equals(updatedDeckConfig, algorithmContext.deckConfig())) {
            updateBuffer.recordUpdate(userDeckId, algorithmContext.algorithmId(), updatedDeckConfig, now)
                    .ifPresent(cfg -> deckAlgorithmPort.updateDeckAlgorithm(userId, userDeckId, algorithmContext.algorithmId(), cfg));
        }
        preferencesService.incrementCounters(userDeckId, current == null, now);

        ReviewNextCardResponse next = nextCard(userId, userDeckId);
        ReviewAnswerResponse.Completion completion = null;
        if (next.userCardId() == null) {
            completion = buildCompletion(userId, userDeckId, now);
            updateBuffer.flushIfPending(userDeckId, algorithmContext.algorithmId(), now)
                    .ifPresent(cfg -> deckAlgorithmPort.updateDeckAlgorithm(userId, userDeckId, algorithmContext.algorithmId(), cfg));
        }
        return new ReviewAnswerResponse(
                userCardId,
                rating,
                computation.nextReviewAt(),
                next,
                completion
        );
    }

    private ReviewAnswerResponse.Completion buildCompletion(UUID userId, UUID userDeckId, Instant now) {
        UserDeckPreferencesService.PreferencesSnapshot preferences = preferencesService.getSnapshot(userDeckId, now);
        UserDeckPreferencesService.ReviewDayBounds reviewDay = preferences.reviewDay(now);
        String timeZone = (preferences.timeZoneId() == null || preferences.timeZoneId().isBlank())
                ? "UTC"
                : preferences.timeZoneId();

        ReviewDayCompletionRepository.CompletionProjection completionProjection =
                reviewDayCompletionRepository.registerCompletion(userId, reviewDay.date(), now);
        int completionIndexToday = completionProjection == null ? 1 : completionProjection.getCompletionsCount();
        boolean firstCompletionToday = completionIndexToday == 1;

        ReviewStatsRepository.StreakProjection streakProjection = reviewStatsRepository.loadStreak(
                userId,
                null,
                now,
                timeZone,
                preferences.dayCutoffMinutes()
        );
        long todayStreak = streakProjection == null ? 0L : Math.max(0L, streakProjection.getTodayStreakDays());
        long currentStreak = streakProjection == null ? 0L : Math.max(0L, streakProjection.getCurrentStreakDays());
        long longestStreak = streakProjection == null ? 0L : Math.max(0L, streakProjection.getLongestStreakDays());
        long previousStreak = firstCompletionToday ? Math.max(0L, todayStreak - 1L) : currentStreak;

        ReviewStatsRepository.SessionWindowProjection sessionProjection = reviewStatsRepository.loadLatestSessionWindow(
                userId,
                userDeckId,
                reviewDay.start(),
                reviewDay.end(),
                timeZone,
                preferences.dayCutoffMinutes(),
                DEFAULT_SESSION_GAP_MINUTES
        );
        ReviewAnswerResponse.SessionSnapshot session = sessionProjection == null
                ? null
                : new ReviewAnswerResponse.SessionSnapshot(
                sessionProjection.getSessionStartedAt(),
                sessionProjection.getSessionEndedAt(),
                sessionProjection.getDurationMinutes(),
                sessionProjection.getReviewCount(),
                sessionProjection.getTotalResponseMs()
        );

        ReviewAnswerResponse.StreakProgress streak = new ReviewAnswerResponse.StreakProgress(
                previousStreak,
                Math.max(currentStreak, todayStreak),
                longestStreak
        );
        return new ReviewAnswerResponse.Completion(
                firstCompletionToday,
                completionIndexToday,
                reviewDay.date(),
                streak,
                session
        );
    }

    @Transactional
    public void seedProgress(UUID userId,
                             UUID userDeckId,
                             List<SeedCardProgressRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        AlgorithmContext ctx = resolveAlgorithmContext(userId, userDeckId);
        List<UUID> cardIds = requests.stream()
                .map(SeedCardProgressRequest::userCardId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (cardIds.isEmpty()) {
            return;
        }
        Map<UUID, ReviewUserCardEntity> cards = new HashMap<>();
        for (ReviewUserCardEntity card : userCardRepo.findAllById(cardIds)) {
            cards.put(card.getUserCardId(), card);
        }
        Map<UUID, SrCardStateEntity> existing = new HashMap<>();
        for (SrCardStateEntity state : stateRepo.findAllById(cardIds)) {
            existing.put(state.getUserCardId(), state);
        }

        Instant now = Instant.now();
        List<SrCardStateEntity> toSave = new java.util.ArrayList<>();

        for (SeedCardProgressRequest request : requests) {
            if (request == null || request.userCardId() == null) {
                continue;
            }
            UUID cardId = request.userCardId();
            ReviewUserCardEntity card = cards.get(cardId);
            if (card == null) {
                throw new IllegalArgumentException("User card not found: " + cardId);
            }
            if (!userId.equals(card.getUserId()) || !userDeckId.equals(card.getUserDeckId())) {
                throw new SecurityException("Access denied to card " + cardId);
            }
            if (card.isDeleted()) {
                continue;
            }
            if (existing.containsKey(cardId)) {
                continue;
            }

            double stability = Math.max(0.1, request.stabilityDays());
            CanonicalProgress progress = new CanonicalProgress(request.difficulty01(), stability);
            JsonNode stateJson = ctx.algorithm().fromCanonical(progress, ctx.effectiveConfig());

            Instant lastReviewAt = request.lastReviewAt() == null ? now : request.lastReviewAt();
            Instant nextReviewAt = request.nextReviewAt();
            if (nextReviewAt == null) {
                nextReviewAt = lastReviewAt.plus(Duration.ofSeconds((long) (stability * 86400)));
            }

            SrCardStateEntity state = new SrCardStateEntity();
            state.setUserCardId(cardId);
            state.setAlgorithmId(ctx.algorithmId());
            state.setState(stateJson);
            state.setLastReviewAt(lastReviewAt);
            state.setNextReviewAt(nextReviewAt);
            state.setReviewCount(Math.max(0, request.reviewCount() == null ? 0 : request.reviewCount()));
            state.setSuspended(request.suspended() != null && request.suspended());
            toSave.add(state);
        }

        if (!toSave.isEmpty()) {
            stateRepo.saveAll(toSave);
        }
    }

    @Transactional
    public ReviewNextCardResponse nextCard(UUID userId, UUID userDeckId) {
        Instant now = Instant.now();
        var preferences = preferencesService.getSnapshot(userDeckId, now);
        AlgorithmContext algorithmContext = resolveAlgorithmContext(userId, userDeckId);

        Instant dueHorizon = now.plus(preferences.learningHorizon());
        Instant reviewDayEnd = preferences.reviewDay(now).end();
        long reviewQuota = preferences.remainingReviewQuota();
        long rawDueNowCount = userCardRepo.countDue(userId, userDeckId, now);
        long dueNowCount = applyReviewQuota(rawDueNowCount, reviewQuota);
        long rawDueHorizonCount = userCardRepo.countDue(userId, userDeckId, dueHorizon);
        long rawDueSoonCount = Math.max(0, rawDueHorizonCount - rawDueNowCount);
        long remainingAfterDue = (reviewQuota == Long.MAX_VALUE)
                ? Long.MAX_VALUE
                : Math.max(0, reviewQuota - dueNowCount);
        long dueSoonCount = (remainingAfterDue == Long.MAX_VALUE)
                ? rawDueSoonCount
                : Math.min(rawDueSoonCount, remainingAfterDue);
        long rawDueTodayCount = userCardRepo.countDue(userId, userDeckId, reviewDayEnd);
        long dueTodayCount = applyReviewQuota(rawDueTodayCount, reviewQuota);
        long newTotalCount = userCardRepo.countNew(userId, userDeckId);
        long remainingNewQuota = preferences.remainingNewQuota();
        long availableNew = newTotalCount;
        if (remainingNewQuota != Long.MAX_VALUE) {
            availableNew = Math.min(availableNew, remainingNewQuota);
        }

        var queue = new ReviewNextCardResponse.QueueSummary(
                dueNowCount,
                availableNew,
                dueTodayCount + availableNew,
                dueTodayCount,
                newTotalCount,
                dueSoonCount
        );

        // 1) due, 2) new
        UUID nextCardId = null;
        boolean due = false;
        boolean learningAhead = false;

        if (dueNowCount > 0) {
            var dueIds = userCardRepo.findDueCardIds(userId, userDeckId, now, PageRequest.of(0, dueCandidateSize()));
            nextCardId = pickSoftRandom(dueIds);
            due = nextCardId != null;
        }
        if (nextCardId == null && availableNew > 0) {
            var newIds = userCardRepo.findNewCardIds(userId, userDeckId, PageRequest.of(0, 1));
            if (!newIds.isEmpty()) {
                nextCardId = newIds.getFirst();
            }
        }
        if (nextCardId == null && dueSoonCount > 0) {
            var learningIds = userCardRepo.findDueCardIds(userId, userDeckId, dueHorizon, PageRequest.of(0, dueCandidateSize()));
            nextCardId = pickSoftRandom(learningIds);
            learningAhead = nextCardId != null;
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
                due,
                queue
        );
    }

    private AlgorithmContext resolveAlgorithmContext(UUID userId, UUID userDeckId) {
        DeckAlgorithmConfig deckAlgo = deckAlgorithmPort.getDeckAlgorithm(userId, userDeckId);
        JsonNode deckConfig = updateBuffer.applyPending(userDeckId, deckAlgo.algorithmId(), deckAlgo.algorithmParams(), Instant.now());
        return buildAlgorithmContext(deckAlgo.algorithmId(), deckConfig);
    }

    private AlgorithmContext buildAlgorithmContext(String algorithmId, JsonNode deckConfig) {
        SrsAlgorithm algorithm = registry.require(algorithmId);
        JsonNode defaultConfig = defaultConfigCache.getDefaultConfig(algorithmId);

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

    private static long applyReviewQuota(long dueCount, long remainingReviewQuota) {
        if (remainingReviewQuota == Long.MAX_VALUE) {
            return dueCount;
        }
        return Math.min(dueCount, remainingReviewQuota);
    }

    private static UUID pickSoftRandom(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (ids.size() == 1) {
            return ids.getFirst();
        }
        double r = java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        int idx = (int) Math.floor(Math.pow(r, 2.0) * ids.size());
        idx = Math.max(0, Math.min(ids.size() - 1, idx));
        return ids.get(idx);
    }

    private static int dueCandidateSize() {
        return 8;
    }

    private void logReview(UUID userCardId,
                           String algorithmId,
                           Rating rating,
                           Integer responseMs,
                           ReviewSource source,
                           JsonNode features,
                           JsonNode stateBefore,
                           JsonNode stateAfter,
                           Instant reviewedAt) {
        SrReviewLogEntity log = new SrReviewLogEntity();
        log.setUserCardId(userCardId);
        log.setAlgorithmId(algorithmId);
        log.setReviewedAt(reviewedAt);
        log.setRating((short) rating.code());
        log.setResponseMs(responseMs);
        log.setSource(source == null ? ReviewSource.other : source);
        log.setFeatures(features);
        log.setStateBefore(stateBefore);
        log.setStateAfter(stateAfter);
        reviewLogRepository.save(log);
    }

    private static JsonNode buildFeatures(SrsAlgorithm.ReviewInput input,
                                          Rating rating,
                                          Integer responseMs,
                                          ReviewSource source,
                                          JsonNode requestFeatures,
                                          Instant now) {
        ObjectNode server = JsonNodeFactory.instance.objectNode();
        server.put("rating", rating.name().toLowerCase());
        server.put("ratingCode", rating.code());
        if (responseMs != null) {
            server.put("responseMs", responseMs);
        }
        if (source != null) {
            server.put("source", source.name());
        }
        server.put("reviewCount", Math.max(0, input.reviewCount()));
        server.put("isNew", input.lastReviewAt() == null);
        if (input.lastReviewAt() != null) {
            double elapsedDays = Math.max(0.0, Duration.between(input.lastReviewAt(), now).toSeconds() / 86400.0);
            server.put("elapsedDays", elapsedDays);
        }

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("server", server);
        if (requestFeatures != null && !requestFeatures.isNull()) {
            out.set("client", requestFeatures);
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
                                                               AlgorithmStats stats,
                                                               UserDeckPreferencesService.PreferencesSnapshot preferences) {
        return new ReviewDeckAlgorithmResponse(
                userDeckId,
                ctx.algorithmId(),
                ctx.deckConfig(),
                ctx.effectiveConfig(),
                toReviewPreferences(preferences),
                stats.activeCards(),
                stats.trackedCards(),
                stats.pendingMigrationCards()
        );
    }

    private static ReviewPreferencesDto toReviewPreferences(UserDeckPreferencesService.PreferencesSnapshot preferences) {
        Integer dailyNewLimit = preferences.maxNewPerDay();
        long minutes = preferences.learningHorizon().toMinutes();
        int learningHorizonHours = Math.max(1, (int) Math.round(minutes / 60.0));
        Integer maxReviewPerDay = preferences.maxReviewPerDay();
        int dayCutoffHour = Math.max(0, Math.min(23, preferences.dayCutoffMinutes() / 60));
        String timeZone = preferences.timeZoneId();
        return new ReviewPreferencesDto(dailyNewLimit, learningHorizonHours, maxReviewPerDay, dayCutoffHour, timeZone);
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
