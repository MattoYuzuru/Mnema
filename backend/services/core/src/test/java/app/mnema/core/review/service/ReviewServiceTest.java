package app.mnema.core.review.service;

import app.mnema.core.review.algorithm.AlgorithmRegistry;
import app.mnema.core.review.algorithm.CanonicalProgress;
import app.mnema.core.review.algorithm.ReviewContext;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.api.CardViewPort;
import app.mnema.core.review.api.DeckAlgorithmConfig;
import app.mnema.core.review.api.DeckAlgorithmPort;
import app.mnema.core.review.controller.dto.ReviewAnswerResponse;
import app.mnema.core.review.controller.dto.ReviewDeckAlgorithmResponse;
import app.mnema.core.review.controller.dto.ReviewNextCardResponse;
import app.mnema.core.review.controller.dto.ReviewPreferencesDto;
import app.mnema.core.review.controller.dto.ReviewSummaryResponse;
import app.mnema.core.review.controller.dto.SeedCardProgressRequest;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.domain.ReviewSource;
import app.mnema.core.review.entity.ReviewUserCardEntity;
import app.mnema.core.review.entity.SrCardStateEntity;
import app.mnema.core.review.entity.SrReviewLogEntity;
import app.mnema.core.review.repository.ReviewDayCompletionRepository;
import app.mnema.core.review.repository.ReviewStatsRepository;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import app.mnema.core.review.repository.SrCardStateRepository;
import app.mnema.core.review.repository.SrReviewLogRepository;
import app.mnema.core.review.service.UserDeckPreferencesService.PreferencesSnapshot;
import app.mnema.core.review.util.JsonConfigMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    ReviewUserCardRepository userCardRepo;

    @Mock
    SrCardStateRepository stateRepo;

    @Mock
    AlgorithmRegistry registry;

    @Mock
    CardViewPort cardViewPort;

    @Mock
    DeckAlgorithmPort deckAlgorithmPort;

    @Mock
    JsonConfigMerger configMerger;

    @Mock
    UserDeckPreferencesService preferencesService;

    @Mock
    SrReviewLogRepository reviewLogRepository;

    @Mock
    ReviewStatsRepository reviewStatsRepository;

    @Mock
    ReviewDayCompletionRepository reviewDayCompletionRepository;

    @Mock
    AlgorithmDefaultConfigCache defaultConfigCache;

    @Mock
    DeckAlgorithmUpdateBuffer updateBuffer;

    ReviewService reviewService;

    @BeforeEach
    void setup() {
        reviewService = new ReviewService(
                userCardRepo,
                stateRepo,
                registry,
                cardViewPort,
                deckAlgorithmPort,
                configMerger,
                preferencesService,
                reviewLogRepository,
                reviewStatsRepository,
                reviewDayCompletionRepository,
                defaultConfigCache,
                updateBuffer
        );
    }

    @Test
    void summary_returnsZeroWhenUserHasNoActiveDecks() {
        UUID userId = UUID.randomUUID();
        when(userCardRepo.findActiveDeckIds(userId)).thenReturn(List.of());

        ReviewSummaryResponse response = reviewService.summary(userId);

        assertThat(response.dueCount()).isZero();
        assertThat(response.newCount()).isZero();
        verify(preferencesService, never()).getSnapshot(any(), any());
    }

    @Test
    void summary_appliesPerDeckReviewAndNewQuotas() {
        UUID userId = UUID.randomUUID();
        UUID deckA = UUID.randomUUID();
        UUID deckB = UUID.randomUUID();

        when(userCardRepo.findActiveDeckIds(userId)).thenReturn(List.of(deckA, deckB));
        when(userCardRepo.countNewByDeck(userId, List.of(deckA, deckB))).thenReturn(List.of(
                deckCount(deckA, 10),
                deckCount(deckB, 2)
        ));
        when(userCardRepo.countDue(eq(userId), eq(deckA), any())).thenReturn(9L);
        when(userCardRepo.countDue(eq(userId), eq(deckB), any())).thenReturn(4L);
        when(preferencesService.getSnapshot(eq(deckA), any())).thenReturn(snapshot(deckA, 2, 5, 7, 2, 1, null, 0));
        when(preferencesService.getSnapshot(eq(deckB), any())).thenReturn(snapshot(deckB, 2, 2, null, 1, 0, null, 0));

        ReviewSummaryResponse response = reviewService.summary(userId);

        assertThat(response.dueCount()).isEqualTo(10);
        assertThat(response.newCount()).isEqualTo(4);
    }

    @Test
    void getDeckAlgorithm_returnsMergedConfigStatsAndPreferences() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        String algorithmId = "sm2";
        JsonNode deckConfig = json("graduatingIntervalDays", 2);
        JsonNode defaultConfig = json("minimumEaseFactor", 1.3);
        JsonNode effectiveConfig = json("effective", true);
        SrsAlgorithm algorithm = algorithmMock(algorithmId);

        stubAlgorithmContext(userId, deckId, algorithmId, deckConfig, defaultConfig, effectiveConfig, algorithm);
        when(userCardRepo.countActive(userId, deckId)).thenReturn(11L);
        when(stateRepo.countTrackedCards(userId, deckId)).thenReturn(8L);
        when(stateRepo.countPendingMigration(userId, deckId, algorithmId)).thenReturn(3L);
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 2, 15, 30, 4, 12, "Europe/Moscow", 120));

        ReviewDeckAlgorithmResponse response = reviewService.getDeckAlgorithm(userId, deckId);

        assertThat(response.algorithmId()).isEqualTo(algorithmId);
        assertThat(response.algorithmParams()).isEqualTo(deckConfig);
        assertThat(response.effectiveAlgorithmParams()).isEqualTo(effectiveConfig);
        assertThat(response.activeCards()).isEqualTo(11);
        assertThat(response.trackedCards()).isEqualTo(8);
        assertThat(response.pendingMigrationCards()).isEqualTo(3);
        assertThat(response.reviewPreferences()).isEqualTo(new ReviewPreferencesDto(15, 2, 30, 2, "Europe/Moscow"));
    }

    @Test
    void updateDeckAlgorithm_rejectsBlankAlgorithmId() {
        assertThatThrownBy(() -> reviewService.updateDeckAlgorithm(
                UUID.randomUUID(),
                UUID.randomUUID(),
                " ",
                MAPPER.createObjectNode(),
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Algorithm id is required");
    }

    @Test
    void updateDeckAlgorithm_updatesPreferencesAndClearsPendingBuffer() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        String algorithmId = "sm2";
        JsonNode override = json("learningStepsMinutes", 1);
        JsonNode defaultCfg = json("minimumEaseFactor", 1.2);
        JsonNode effective = json("effective", true);
        SrsAlgorithm algorithm = algorithmMock(algorithmId);

        when(registry.require(algorithmId)).thenReturn(algorithm);
        when(deckAlgorithmPort.updateDeckAlgorithm(userId, deckId, algorithmId, override))
                .thenReturn(new DeckAlgorithmConfig(algorithmId, override));
        when(defaultConfigCache.getDefaultConfig(algorithmId)).thenReturn(defaultCfg);
        when(configMerger.merge(defaultCfg, override)).thenReturn(effective);
        when(userCardRepo.countActive(userId, deckId)).thenReturn(10L);
        when(stateRepo.countTrackedCards(userId, deckId)).thenReturn(6L);
        when(stateRepo.countPendingMigration(userId, deckId, algorithmId)).thenReturn(1L);

        PreferencesSnapshot updatedPreferences = snapshot(deckId, 3, 12, 50, 1, 2, "Europe/Moscow", 180);
        ReviewPreferencesDto dto = new ReviewPreferencesDto(12, 3, 50, 3, "Europe/Moscow");
        when(preferencesService.updatePreferences(deckId, 12, 3, 50, 3, "Europe/Moscow")).thenReturn(updatedPreferences);

        ReviewDeckAlgorithmResponse response = reviewService.updateDeckAlgorithm(userId, deckId, algorithmId, override, dto);

        verify(updateBuffer).clear(deckId);
        verify(preferencesService).updatePreferences(deckId, 12, 3, 50, 3, "Europe/Moscow");
        assertThat(response.reviewPreferences()).isEqualTo(dto);
        assertThat(response.pendingMigrationCards()).isEqualTo(1L);
    }

    @Test
    void answer_rejectsCardFromDifferentDeck() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID foreignDeckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        stubAlgorithmContext(userId, deckId, "sm2", json("deck", true), json("default", true), json("effective", true), algorithmMock("sm2"));
        ReviewUserCardEntity card = card(userId, foreignDeckId, false, cardId);
        when(userCardRepo.findByUserCardIdAndUserId(cardId, userId)).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> reviewService.answer(userId, deckId, cardId, Rating.GOOD, 400, ReviewSource.web, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining(cardId.toString());
    }

    @Test
    void answer_rejectsDeletedCard() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        stubAlgorithmContext(userId, deckId, "sm2", json("deck", true), json("default", true), json("effective", true), algorithmMock("sm2"));
        when(userCardRepo.findByUserCardIdAndUserId(cardId, userId)).thenReturn(Optional.of(card(userId, deckId, true, cardId)));

        assertThatThrownBy(() -> reviewService.answer(userId, deckId, cardId, Rating.GOOD, 400, ReviewSource.web, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Card is deleted");
    }

    @Test
    void answer_newCardWritesLogFlushesPendingConfigAndBuildsCompletion() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        String algorithmId = "hlr";
        JsonNode deckConfig = json("requestRetention", 0.9);
        JsonNode defaultConfig = json("minimumIntervalMinutes", 1);
        JsonNode effectiveConfig = json("effective", true);
        JsonNode initialState = json("phase", "learning");
        JsonNode newState = json("phase", "review");
        JsonNode immediateDeckUpdate = json("weightsVersion", 1);
        JsonNode finalDeckUpdate = json("weightsVersion", 2);
        JsonNode clientFeatures = json("x", 1);

        SrsAlgorithm algorithm = algorithmMock(algorithmId);
        stubAlgorithmContext(userId, deckId, algorithmId, deckConfig, defaultConfig, effectiveConfig, algorithm);
        when(algorithm.initialState(effectiveConfig)).thenReturn(initialState);

        Instant fixedNow = Instant.parse("2026-04-07T11:00:00Z");
        when(algorithm.review(any(), eq(Rating.GOOD), any(), eq(effectiveConfig), any(), eq(deckConfig)))
                .thenReturn(new SrsAlgorithm.ReviewOutcome(
                        new SrsAlgorithm.ReviewComputation(newState, fixedNow.plus(Duration.ofDays(2)), fixedNow, 1),
                        immediateDeckUpdate
                ));
        when(updateBuffer.recordUpdate(eq(deckId), eq(algorithmId), eq(immediateDeckUpdate), any())).thenReturn(Optional.of(immediateDeckUpdate));
        when(updateBuffer.flushIfPending(eq(deckId), eq(algorithmId), any())).thenReturn(Optional.of(finalDeckUpdate));
        when(userCardRepo.findByUserCardIdAndUserId(cardId, userId)).thenReturn(Optional.of(card(userId, deckId, false, cardId)));
        when(stateRepo.findByIdForUpdate(cardId)).thenReturn(Optional.empty());
        when(stateRepo.save(any(SrCardStateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 2, 20, null, 0, 0, null, 0));
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(0L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(0L);
        when(reviewDayCompletionRepository.registerCompletion(eq(userId), any(), any())).thenReturn(completion(1));
        when(reviewStatsRepository.loadStreak(eq(userId), isNull(), any(), eq("UTC"), eq(0))).thenReturn(streak(7, 12, 7));
        when(reviewStatsRepository.loadLatestSessionWindow(eq(userId), eq(deckId), any(), any(), eq("UTC"), eq(0), eq(30))).thenReturn(session(
                fixedNow.minus(Duration.ofMinutes(20)),
                fixedNow,
                20,
                9,
                4300
        ));

        ReviewAnswerResponse response = reviewService.answer(userId, deckId, cardId, Rating.GOOD, 850, null, clientFeatures);

        ArgumentCaptor<ReviewContext> contextCaptor = ArgumentCaptor.forClass(ReviewContext.class);
        verify(algorithm).review(any(), eq(Rating.GOOD), any(), eq(effectiveConfig), contextCaptor.capture(), eq(deckConfig));
        assertThat(contextCaptor.getValue().source()).isEqualTo(ReviewSource.other);
        assertThat(contextCaptor.getValue().features().path("server").path("isNew").asBoolean()).isTrue();
        assertThat(contextCaptor.getValue().features().path("client")).isEqualTo(clientFeatures);

        ArgumentCaptor<SrReviewLogEntity> logCaptor = ArgumentCaptor.forClass(SrReviewLogEntity.class);
        verify(reviewLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getSource()).isEqualTo(ReviewSource.other);
        assertThat(logCaptor.getValue().getFeatures().path("server").path("responseMs").asInt()).isEqualTo(850);

        ArgumentCaptor<SrCardStateEntity> stateCaptor = ArgumentCaptor.forClass(SrCardStateEntity.class);
        verify(stateRepo).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getAlgorithmId()).isEqualTo(algorithmId);
        assertThat(stateCaptor.getValue().getState()).isEqualTo(newState);
        assertThat(stateCaptor.getValue().getReviewCount()).isEqualTo(1);
        assertThat(stateCaptor.getValue().isSuspended()).isFalse();

        verify(preferencesService).incrementCounters(eq(deckId), eq(true), any());
        verify(deckAlgorithmPort).updateDeckAlgorithm(userId, deckId, algorithmId, immediateDeckUpdate);
        verify(deckAlgorithmPort).updateDeckAlgorithm(userId, deckId, algorithmId, finalDeckUpdate);
        assertThat(response.next().userCardId()).isNull();
        assertThat(response.completion()).isNotNull();
        assertThat(response.completion().firstCompletionToday()).isTrue();
        assertThat(response.completion().completionIndexToday()).isEqualTo(1);
        assertThat(response.completion().streak().previousStreakDays()).isEqualTo(6);
        assertThat(response.completion().session().reviewCount()).isEqualTo(9);
    }

    @Test
    void answer_returnsNextDueCardWithoutCompletion() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID answeredCardId = UUID.randomUUID();
        UUID nextCardId = UUID.randomUUID();
        String algorithmId = "fsrs_v6";
        JsonNode deckConfig = json("requestRetention", 0.9);
        JsonNode defaultConfig = json("weights", 1);
        JsonNode effectiveConfig = json("effective", true);
        JsonNode currentState = json("phase", "review");
        JsonNode newState = json("phase", "review", "s", 7.0);

        SrsAlgorithm algorithm = algorithmMock(algorithmId);
        stubAlgorithmContext(userId, deckId, algorithmId, deckConfig, defaultConfig, effectiveConfig, algorithm);
        when(algorithm.review(any(), eq(Rating.GOOD), any(), eq(effectiveConfig), any(), eq(deckConfig)))
                .thenReturn(new SrsAlgorithm.ReviewOutcome(
                        new SrsAlgorithm.ReviewComputation(newState, Instant.parse("2026-04-09T10:00:00Z"), Instant.parse("2026-04-07T10:00:00Z"), 1),
                        null
                ));
        when(algorithm.previewNextReviewAt(any(), any(), eq(effectiveConfig))).thenAnswer(invocation -> {
            Instant now = invocation.getArgument(1);
            Map<Rating, Instant> nextAt = new EnumMap<>(Rating.class);
            nextAt.put(Rating.AGAIN, now.plus(Duration.ofMinutes(1)));
            nextAt.put(Rating.HARD, now.plus(Duration.ofHours(1)));
            nextAt.put(Rating.GOOD, now.plus(Duration.ofDays(1)));
            nextAt.put(Rating.EASY, now.plus(Duration.ofDays(4)));
            return nextAt;
        });

        when(userCardRepo.findByUserCardIdAndUserId(answeredCardId, userId)).thenReturn(Optional.of(card(userId, deckId, false, answeredCardId)));
        SrCardStateEntity trackedState = state(algorithmId, currentState, Instant.parse("2026-04-06T10:00:00Z"), Instant.parse("2026-04-07T09:00:00Z"), 4, false, answeredCardId);
        when(stateRepo.findByIdForUpdate(answeredCardId)).thenReturn(Optional.of(trackedState));
        when(stateRepo.save(any(SrCardStateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 2, 20, 20, 0, 0, null, 0));
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(1L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(0L);
        when(userCardRepo.findDueCardIds(eq(userId), eq(deckId), any(), any())).thenReturn(List.of(nextCardId));
        when(cardViewPort.getCardViews(userId, List.of(nextCardId))).thenReturn(List.of(
                new CardViewPort.CardView(nextCardId, UUID.randomUUID(), true, json("front", "Q"))
        ));
        when(stateRepo.findById(nextCardId)).thenReturn(Optional.of(state(algorithmId, json("phase", "review"), Instant.parse("2026-04-07T08:00:00Z"), Instant.parse("2026-04-07T09:30:00Z"), 1, false, nextCardId)));

        ReviewAnswerResponse response = reviewService.answer(userId, deckId, answeredCardId, Rating.GOOD, 1200, ReviewSource.web, null);

        verify(preferencesService).incrementCounters(eq(deckId), eq(false), any());
        assertThat(response.completion()).isNull();
        assertThat(response.next().userCardId()).isEqualTo(nextCardId);
        assertThat(response.next().due()).isTrue();
        assertThat(response.next().intervals().get(Rating.AGAIN).display()).isEqualTo("1m");
        assertThat(response.next().intervals().get(Rating.HARD).display()).isEqualTo("1h");
        assertThat(response.next().intervals().get(Rating.GOOD).display()).isEqualTo("1d");
        assertThat(response.next().intervals().get(Rating.EASY).display()).isEqualTo("4d");
    }

    @Test
    void seedProgress_ignoresDeletedExistingAndDuplicateRequests() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID validCardId = UUID.randomUUID();
        UUID deletedCardId = UUID.randomUUID();
        UUID existingCardId = UUID.randomUUID();
        String algorithmId = "sm2";
        JsonNode deckConfig = json("deck", true);
        JsonNode defaultConfig = json("default", true);
        JsonNode effectiveConfig = json("effective", true);
        JsonNode seededState = json("phase", "review", "stability", 1);

        SrsAlgorithm algorithm = algorithmMock(algorithmId);
        stubAlgorithmContext(userId, deckId, algorithmId, deckConfig, defaultConfig, effectiveConfig, algorithm);
        when(algorithm.fromCanonical(any(), eq(effectiveConfig))).thenReturn(seededState);

        when(userCardRepo.findAllById(any())).thenReturn(List.of(
                card(userId, deckId, false, validCardId),
                card(userId, deckId, true, deletedCardId),
                card(userId, deckId, false, existingCardId)
        ));
        when(stateRepo.findAllById(any())).thenReturn(List.of(
                state(algorithmId, json("phase", "review"), Instant.parse("2026-04-06T08:00:00Z"), Instant.parse("2026-04-08T08:00:00Z"), 2, false, existingCardId)
        ));

        Instant lastReviewAt = Instant.parse("2026-04-01T12:00:00Z");
        List<SeedCardProgressRequest> requests = new ArrayList<>();
        requests.add(null);
        requests.add(new SeedCardProgressRequest(null, 0.5, 4.0, 1, lastReviewAt, null, false));
        requests.add(new SeedCardProgressRequest(validCardId, 0.75, 0.0, -2, lastReviewAt, null, true));
        requests.add(new SeedCardProgressRequest(validCardId, 0.2, 8.0, 3, lastReviewAt, lastReviewAt.plus(Duration.ofDays(8)), false));
        requests.add(new SeedCardProgressRequest(deletedCardId, 0.5, 2.0, 1, lastReviewAt, null, false));
        requests.add(new SeedCardProgressRequest(existingCardId, 0.5, 2.0, 1, lastReviewAt, null, false));

        reviewService.seedProgress(userId, deckId, requests);

        ArgumentCaptor<Iterable<SrCardStateEntity>> saveCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(stateRepo).saveAll(saveCaptor.capture());
        List<SrCardStateEntity> saved = new ArrayList<>();
        saveCaptor.getValue().forEach(saved::add);

        assertThat(saved).hasSize(1);
        SrCardStateEntity state = saved.getFirst();
        assertThat(state.getUserCardId()).isEqualTo(validCardId);
        assertThat(state.getAlgorithmId()).isEqualTo(algorithmId);
        assertThat(state.getState()).isEqualTo(seededState);
        assertThat(state.getReviewCount()).isZero();
        assertThat(state.isSuspended()).isTrue();
        assertThat(state.getLastReviewAt()).isEqualTo(lastReviewAt);
        assertThat(state.getNextReviewAt()).isEqualTo(lastReviewAt.plus(Duration.ofSeconds(8640)));
    }

    @Test
    void seedProgress_rejectsForeignCard() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID foreignDeckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        stubAlgorithmContext(userId, deckId, "sm2", json("deck", true), json("default", true), json("effective", true), algorithmMock("sm2"));
        when(userCardRepo.findAllById(any())).thenReturn(List.of(card(userId, foreignDeckId, false, cardId)));
        when(stateRepo.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> reviewService.seedProgress(userId, deckId, List.of(
                new SeedCardProgressRequest(cardId, 0.4, 2.0, 1, Instant.parse("2026-04-01T00:00:00Z"), null, false)
        ))).isInstanceOf(SecurityException.class)
                .hasMessageContaining(cardId.toString());
    }

    @Test
    void nextCard_returnsEmptyResponseWhenNothingIsAvailable() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        String algorithmId = "sm2";

        stubAlgorithmContext(userId, deckId, algorithmId, json("deck", true), json("default", true), json("effective", true), algorithmMock(algorithmId));
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 2, 20, 20, 0, 0, null, 0));
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(0L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(0L);

        ReviewNextCardResponse response = reviewService.nextCard(userId, deckId);

        assertThat(response.userCardId()).isNull();
        assertThat(response.queue().dueCount()).isZero();
        assertThat(response.queue().newCount()).isZero();
        assertThat(response.queue().learningAheadCount()).isZero();
    }

    @Test
    void nextCard_prefersNewCardsWhenNothingIsDueNow() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID newCardId = UUID.randomUUID();
        String algorithmId = "sm2";
        JsonNode effectiveConfig = json("effective", true);

        SrsAlgorithm algorithm = algorithmMock(algorithmId);
        stubAlgorithmContext(userId, deckId, algorithmId, json("deck", true), json("default", true), effectiveConfig, algorithm);
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 2, 2, 20, 1, 0, null, 0));
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(0L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(2L);
        when(userCardRepo.findNewCardIds(userId, deckId, org.springframework.data.domain.PageRequest.of(0, 1))).thenReturn(List.of(newCardId));
        when(cardViewPort.getCardViews(userId, List.of(newCardId))).thenReturn(List.of(
                new CardViewPort.CardView(newCardId, UUID.randomUUID(), false, json("front", "new"))
        ));
        when(stateRepo.findById(newCardId)).thenReturn(Optional.empty());
        when(algorithm.initialState(effectiveConfig)).thenReturn(json("phase", "learning"));
        when(algorithm.previewNextReviewAt(any(), any(), eq(effectiveConfig))).thenAnswer(invocation -> preview((Instant) invocation.getArgument(1)));

        ReviewNextCardResponse response = reviewService.nextCard(userId, deckId);

        assertThat(response.userCardId()).isEqualTo(newCardId);
        assertThat(response.due()).isFalse();
        assertThat(response.queue().newCount()).isEqualTo(1);
        assertThat(response.queue().newTotalCount()).isEqualTo(2);
    }

    @Test
    void nextCard_usesLearningAheadWhenOnlyFutureDueCardsRemain() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID learningCardId = UUID.randomUUID();
        String algorithmId = "fsrs_v6";
        JsonNode effectiveConfig = json("effective", true);

        SrsAlgorithm algorithm = algorithmMock(algorithmId);
        stubAlgorithmContext(userId, deckId, algorithmId, json("deck", true), json("default", true), effectiveConfig, algorithm);
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 3, 20, 5, 0, 1, null, 0));
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(0L, 3L, 4L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(0L);
        when(userCardRepo.findDueCardIds(eq(userId), eq(deckId), any(), any())).thenReturn(List.of(learningCardId));
        when(cardViewPort.getCardViews(userId, List.of(learningCardId))).thenReturn(List.of(
                new CardViewPort.CardView(learningCardId, UUID.randomUUID(), true, json("front", "ahead"))
        ));
        when(stateRepo.findById(learningCardId)).thenReturn(Optional.of(state(algorithmId, json("phase", "review"), Instant.parse("2026-04-06T08:00:00Z"), Instant.parse("2026-04-07T14:00:00Z"), 2, false, learningCardId)));
        when(algorithm.previewNextReviewAt(any(), any(), eq(effectiveConfig))).thenAnswer(invocation -> preview((Instant) invocation.getArgument(1)));

        ReviewNextCardResponse response = reviewService.nextCard(userId, deckId);

        assertThat(response.userCardId()).isEqualTo(learningCardId);
        assertThat(response.due()).isFalse();
        assertThat(response.queue().learningAheadCount()).isEqualTo(3);
        assertThat(response.queue().dueTodayCount()).isEqualTo(4);
    }

    @Test
    void nextCard_throwsWhenCardViewPortReturnsEmptyForSelectedCard() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID dueCardId = UUID.randomUUID();

        stubAlgorithmContext(userId, deckId, "sm2", json("deck", true), json("default", true), json("effective", true), algorithmMock("sm2"));
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 2, 20, 20, 0, 0, null, 0));
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(1L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(0L);
        when(userCardRepo.findDueCardIds(eq(userId), eq(deckId), any(), any())).thenReturn(List.of(dueCardId));
        when(cardViewPort.getCardViews(userId, List.of(dueCardId))).thenReturn(List.of());

        assertThatThrownBy(() -> reviewService.nextCard(userId, deckId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(dueCardId.toString());
    }

    @Test
    void answer_convertsLegacyStateWhenAlgorithmChanged() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();

        JsonNode deckConfig = MAPPER.createObjectNode().put("requestRetention", 0.9);
        JsonNode defaultCfg = MAPPER.createObjectNode().put("default", true);
        JsonNode effectiveCfg = MAPPER.createObjectNode().put("effective", true);

        DeckAlgorithmConfig deckAlgo = new DeckAlgorithmConfig("sm2", deckConfig);
        when(deckAlgorithmPort.getDeckAlgorithm(userId, deckId)).thenReturn(deckAlgo);

        when(defaultConfigCache.getDefaultConfig("sm2")).thenReturn(defaultCfg);
        when(updateBuffer.applyPending(eq(deckId), eq("sm2"), eq(deckConfig), any())).thenReturn(deckConfig);
        when(updateBuffer.flushIfPending(eq(deckId), eq("sm2"), any())).thenReturn(Optional.empty());
        when(configMerger.merge(defaultCfg, deckConfig)).thenReturn(effectiveCfg);

        SrsAlgorithm newAlgorithm = algorithmMock("sm2");
        SrsAlgorithm legacyAlgorithm = algorithmMock("fsrs_v6");
        when(registry.require("sm2")).thenReturn(newAlgorithm);
        when(registry.require("fsrs_v6")).thenReturn(legacyAlgorithm);

        ReviewUserCardEntity card = card(userId, deckId, false, cardId);
        when(userCardRepo.findByUserCardIdAndUserId(cardId, userId)).thenReturn(Optional.of(card));

        SrCardStateEntity legacyState = state("fsrs_v6", MAPPER.createObjectNode().put("legacy", true),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), 2, false, cardId);
        when(stateRepo.findByIdForUpdate(cardId)).thenReturn(Optional.of(legacyState));
        when(stateRepo.save(any(SrCardStateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CanonicalProgress canonicalProgress = new CanonicalProgress(0.4, 5.0);
        when(legacyAlgorithm.toCanonical(legacyState.getState())).thenReturn(canonicalProgress);
        JsonNode convertedState = MAPPER.createObjectNode().put("converted", true);
        when(newAlgorithm.fromCanonical(canonicalProgress, effectiveCfg)).thenReturn(convertedState);

        JsonNode newState = MAPPER.createObjectNode().put("new", true);
        Instant now = Instant.now();
        when(newAlgorithm.review(any(), eq(Rating.GOOD), any(), eq(effectiveCfg), any(), any())).thenReturn(
                new SrsAlgorithm.ReviewOutcome(
                        new SrsAlgorithm.ReviewComputation(newState, now.plusSeconds(600), now, 1),
                        null
                )
        );

        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot(deckId, 1, 20, null, 0, 0, null, 0));
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(0L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(0L);
        when(reviewDayCompletionRepository.registerCompletion(eq(userId), any(), any())).thenReturn(completion(1));
        when(reviewStatsRepository.loadStreak(eq(userId), isNull(), any(), eq("UTC"), eq(0))).thenReturn(streak(7, 11, 7));
        when(reviewStatsRepository.loadLatestSessionWindow(eq(userId), eq(deckId), any(), any(), eq("UTC"), eq(0), eq(30))).thenReturn(session(
                now.minusSeconds(1800),
                now,
                30,
                12,
                7300
        ));

        ReviewAnswerResponse response = reviewService.answer(userId, deckId, cardId, Rating.GOOD, 1200, ReviewSource.web, null);

        ArgumentCaptor<SrsAlgorithm.ReviewInput> inputCaptor = ArgumentCaptor.forClass(SrsAlgorithm.ReviewInput.class);
        verify(newAlgorithm).review(inputCaptor.capture(), eq(Rating.GOOD), any(), eq(effectiveCfg), any(), any());
        assertThat(inputCaptor.getValue().state()).isEqualTo(convertedState);
        verify(preferencesService).incrementCounters(eq(deckId), eq(false), any());
        assertThat(response.completion()).isNotNull();
        assertThat(response.completion().firstCompletionToday()).isTrue();
        assertThat(response.completion().streak().currentStreakDays()).isEqualTo(7);
    }

    private static Map<Rating, Instant> preview(Instant now) {
        Map<Rating, Instant> preview = new EnumMap<>(Rating.class);
        preview.put(Rating.AGAIN, now.plus(Duration.ofMinutes(1)));
        preview.put(Rating.HARD, now.plus(Duration.ofHours(1)));
        preview.put(Rating.GOOD, now.plus(Duration.ofDays(1)));
        preview.put(Rating.EASY, now.plus(Duration.ofDays(3)));
        return preview;
    }

    private void stubAlgorithmContext(UUID userId,
                                      UUID deckId,
                                      String algorithmId,
                                      JsonNode deckConfig,
                                      JsonNode defaultConfig,
                                      JsonNode effectiveConfig,
                                      SrsAlgorithm algorithm) {
        when(deckAlgorithmPort.getDeckAlgorithm(userId, deckId)).thenReturn(new DeckAlgorithmConfig(algorithmId, deckConfig));
        when(updateBuffer.applyPending(eq(deckId), eq(algorithmId), eq(deckConfig), any())).thenReturn(deckConfig);
        when(defaultConfigCache.getDefaultConfig(algorithmId)).thenReturn(defaultConfig);
        when(configMerger.merge(defaultConfig, deckConfig)).thenReturn(effectiveConfig);
        when(registry.require(algorithmId)).thenReturn(algorithm);
    }

    private static SrsAlgorithm algorithmMock(String id) {
        SrsAlgorithm algorithm = org.mockito.Mockito.mock(SrsAlgorithm.class);
        return algorithm;
    }

    private static ReviewUserCardRepository.DeckCount deckCount(UUID deckId, long count) {
        return new ReviewUserCardRepository.DeckCount() {
            @Override
            public UUID getUserDeckId() {
                return deckId;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private static PreferencesSnapshot snapshot(UUID deckId,
                                                int learningHorizonHours,
                                                Integer maxNewPerDay,
                                                Integer maxReviewPerDay,
                                                int newSeenToday,
                                                int reviewSeenToday,
                                                String timeZoneId,
                                                int dayCutoffMinutes) {
        return new PreferencesSnapshot(
                deckId,
                Duration.ofHours(learningHorizonHours),
                maxNewPerDay,
                maxReviewPerDay,
                newSeenToday,
                reviewSeenToday,
                timeZoneId,
                dayCutoffMinutes
        );
    }

    private static ReviewUserCardEntity card(UUID userId, UUID deckId, boolean deleted, UUID cardId) {
        ReviewUserCardEntity card = new ReviewUserCardEntity();
        setField(card, "userId", userId);
        setField(card, "userDeckId", deckId);
        setField(card, "userCardId", cardId);
        setField(card, "deleted", deleted);
        return card;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to set field " + fieldName, ex);
        }
    }

    private static SrCardStateEntity state(String algorithmId,
                                           JsonNode stateJson,
                                           Instant lastReviewAt,
                                           Instant nextReviewAt,
                                           int reviewCount,
                                           boolean suspended,
                                           UUID userCardId) {
        SrCardStateEntity state = new SrCardStateEntity();
        state.setUserCardId(userCardId);
        state.setAlgorithmId(algorithmId);
        state.setState(stateJson);
        state.setLastReviewAt(lastReviewAt);
        state.setNextReviewAt(nextReviewAt);
        state.setReviewCount(reviewCount);
        state.setSuspended(suspended);
        return state;
    }

    private static ReviewDayCompletionRepository.CompletionProjection completion(int count) {
        return new ReviewDayCompletionRepository.CompletionProjection() {
            @Override
            public int getCompletionsCount() {
                return count;
            }

            @Override
            public Instant getFirstCompletedAt() {
                return Instant.parse("2026-04-07T09:00:00Z");
            }

            @Override
            public Instant getLastCompletedAt() {
                return Instant.parse("2026-04-07T11:00:00Z");
            }
        };
    }

    private static ReviewStatsRepository.StreakProjection streak(long current, long longest, long today) {
        return new ReviewStatsRepository.StreakProjection() {
            @Override
            public long getCurrentStreakDays() {
                return current;
            }

            @Override
            public long getLongestStreakDays() {
                return longest;
            }

            @Override
            public long getTodayStreakDays() {
                return today;
            }

            @Override
            public boolean getActiveToday() {
                return today > 0;
            }

            @Override
            public LocalDate getCurrentStreakStartDate() {
                return null;
            }

            @Override
            public LocalDate getCurrentStreakEndDate() {
                return null;
            }

            @Override
            public LocalDate getLastActiveDate() {
                return null;
            }
        };
    }

    private static ReviewStatsRepository.SessionWindowProjection session(Instant startedAt,
                                                                         Instant endedAt,
                                                                         long durationMinutes,
                                                                         long reviewCount,
                                                                         long totalResponseMs) {
        return new ReviewStatsRepository.SessionWindowProjection() {
            @Override
            public Instant getSessionStartedAt() {
                return startedAt;
            }

            @Override
            public Instant getSessionEndedAt() {
                return endedAt;
            }

            @Override
            public long getDurationMinutes() {
                return durationMinutes;
            }

            @Override
            public long getReviewCount() {
                return reviewCount;
            }

            @Override
            public long getTotalResponseMs() {
                return totalResponseMs;
            }
        };
    }

    private static JsonNode json(String key, int value) {
        return MAPPER.createObjectNode().put(key, value);
    }

    private static JsonNode json(String key, double value) {
        return MAPPER.createObjectNode().put(key, value);
    }

    private static JsonNode json(String key, boolean value) {
        return MAPPER.createObjectNode().put(key, value);
    }

    private static JsonNode json(String key, String value) {
        return MAPPER.createObjectNode().put(key, value);
    }

    private static JsonNode json(String key1, String value1, String key2, double value2) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put(key1, value1);
        node.put(key2, value2);
        return node;
    }
}
