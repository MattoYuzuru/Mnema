package app.mnema.core.review.service;

import app.mnema.core.review.algorithm.AlgorithmRegistry;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.api.CardViewPort;
import app.mnema.core.review.api.DeckAlgorithmPort;
import app.mnema.core.review.controller.dto.ReviewNextCardResponse;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import app.mnema.core.review.repository.SrCardStateRepository;
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
    public ReviewNextCardResponse next(UUID userId, UUID userDeckId) {
        Instant now = Instant.now();

        long dueCount = userCardRepo.countDue(userId, userDeckId, now);
        long newCount = userCardRepo.countNew(userId, userDeckId);

        var queue = new ReviewNextCardResponse.QueueSummary(
                dueCount,
                newCount,
                dueCount + newCount
        );

        // Настройки алгоритма на колоду
        var deckAlgo = deckAlgorithmPort.getDeckAlgorithm(userId, userDeckId);
        String algorithmId = deckAlgo.algorithmId();
        var algo = registry.require(algorithmId);
        var effectiveConfig = deckAlgo.algorithmParams();

        // 1) due, 2) new
        UUID nextCardId = null;
        boolean due = false;

        var dueIds = userCardRepo.findDueCardIds(userId, userDeckId, now, PageRequest.of(0, 1));
        if (!dueIds.isEmpty()) {
            nextCardId = dueIds.getFirst();
            due = true;
        } else {
            var newIds = userCardRepo.findNewCardIds(userId, userDeckId, PageRequest.of(0, 1));
            if (!newIds.isEmpty()) {
                nextCardId = newIds.getFirst();
            }
        }

        if (nextCardId == null) {
            return new ReviewNextCardResponse(
                    userDeckId,
                    algorithmId,
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

        SrsAlgorithm.ReviewInput input;
        Instant dueAt = null;

        if (stateOpt.isPresent()) {
            var s = stateOpt.get();
            input = new SrsAlgorithm.ReviewInput(
                    s.getState(),
                    s.getLastReviewAt(),
                    s.getReviewCount()
            );
            dueAt = s.getNextReviewAt();
        } else {
            input = new SrsAlgorithm.ReviewInput(
                    algo.initialState(effectiveConfig),
                    null,
                    0
            );
        }

        // Preview интервалов под 4 кнопки
        Map<Rating, Instant> nextAt = algo.previewNextReviewAt(input, now, effectiveConfig);
        Map<Rating, ReviewNextCardResponse.IntervalPreview> intervals = toIntervalPreview(nextAt, now);

        return new ReviewNextCardResponse(
                userDeckId,
                algorithmId,
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
}
