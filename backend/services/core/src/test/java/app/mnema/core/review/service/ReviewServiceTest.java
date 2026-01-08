package app.mnema.core.review.service;

import app.mnema.core.review.algorithm.AlgorithmRegistry;
import app.mnema.core.review.algorithm.CanonicalProgress;
import app.mnema.core.review.algorithm.SrsAlgorithm;
import app.mnema.core.review.api.CardViewPort;
import app.mnema.core.review.api.DeckAlgorithmConfig;
import app.mnema.core.review.api.DeckAlgorithmPort;
import app.mnema.core.review.controller.dto.ReviewDeckAlgorithmResponse;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.entity.ReviewUserCardEntity;
import app.mnema.core.review.entity.SrAlgorithmEntity;
import app.mnema.core.review.entity.SrCardStateEntity;
import app.mnema.core.review.repository.ReviewUserCardRepository;
import app.mnema.core.review.repository.SrAlgorithmRepository;
import app.mnema.core.review.repository.SrCardStateRepository;
import app.mnema.core.review.service.UserDeckPreferencesService.PreferencesSnapshot;
import app.mnema.core.review.util.JsonConfigMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    ReviewUserCardRepository userCardRepo;

    @Mock
    SrCardStateRepository stateRepo;

    @Mock
    SrAlgorithmRepository algorithmRepo;

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

    ReviewService reviewService;

    @BeforeEach
    void setup() {
        reviewService = new ReviewService(
                userCardRepo,
                stateRepo,
                algorithmRepo,
                registry,
                cardViewPort,
                deckAlgorithmPort,
                configMerger,
                preferencesService
        );
    }

    @Test
    void updateDeckAlgorithm_mergesConfigAndReturnsStats() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();

        String algorithmId = "sm2";
        JsonNode override = MAPPER.createObjectNode().put("learningStepsMinutes", 1);
        JsonNode defaultCfg = MAPPER.createObjectNode().put("minimumEaseFactor", 1.2);
        JsonNode effective = MAPPER.createObjectNode().put("combined", true);

        SrAlgorithmEntity algoEntity = new SrAlgorithmEntity();
        algoEntity.setAlgorithmId(algorithmId);
        algoEntity.setDefaultConfig(defaultCfg);

        when(algorithmRepo.findById(algorithmId)).thenReturn(Optional.of(algoEntity));
        when(configMerger.merge(defaultCfg, override)).thenReturn(effective);

        SrsAlgorithm algorithm = mock(SrsAlgorithm.class);
        when(registry.require(algorithmId)).thenReturn(algorithm);

        DeckAlgorithmConfig updated = new DeckAlgorithmConfig(algorithmId, override);
        when(deckAlgorithmPort.updateDeckAlgorithm(userId, deckId, algorithmId, override)).thenReturn(updated);

        when(userCardRepo.countActive(userId, deckId)).thenReturn(10L);
        when(stateRepo.countTrackedCards(userId, deckId)).thenReturn(6L);
        when(stateRepo.countPendingMigration(userId, deckId, algorithmId)).thenReturn(4L);
        PreferencesSnapshot snapshot = new PreferencesSnapshot(
                deckId,
                Duration.ofMinutes(120),
                20,
                null,
                0,
                0
        );
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot);

        ReviewDeckAlgorithmResponse resp = reviewService.updateDeckAlgorithm(userId, deckId, algorithmId, override, null);

        assertThat(resp.userDeckId()).isEqualTo(deckId);
        assertThat(resp.algorithmId()).isEqualTo(algorithmId);
        assertThat(resp.algorithmParams()).isEqualTo(override);
        assertThat(resp.effectiveAlgorithmParams()).isEqualTo(effective);
        assertThat(resp.activeCards()).isEqualTo(10L);
        assertThat(resp.trackedCards()).isEqualTo(6L);
        assertThat(resp.pendingMigrationCards()).isEqualTo(4L);

        verify(deckAlgorithmPort).updateDeckAlgorithm(userId, deckId, algorithmId, override);
        verify(registry, atLeastOnce()).require(algorithmId);
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

        SrAlgorithmEntity algoEntity = new SrAlgorithmEntity();
        algoEntity.setAlgorithmId("sm2");
        algoEntity.setDefaultConfig(defaultCfg);
        when(algorithmRepo.findById("sm2")).thenReturn(Optional.of(algoEntity));
        when(configMerger.merge(defaultCfg, deckConfig)).thenReturn(effectiveCfg);

        SrsAlgorithm newAlgorithm = mock(SrsAlgorithm.class);
        SrsAlgorithm legacyAlgorithm = mock(SrsAlgorithm.class);
        when(registry.require("sm2")).thenReturn(newAlgorithm);
        when(registry.require("fsrs_v6")).thenReturn(legacyAlgorithm);

        ReviewUserCardEntity card = mock(ReviewUserCardEntity.class);
        when(card.getUserDeckId()).thenReturn(deckId);
        when(card.isDeleted()).thenReturn(false);
        when(userCardRepo.findByUserCardIdAndUserId(cardId, userId)).thenReturn(Optional.of(card));

        SrCardStateEntity legacyState = new SrCardStateEntity();
        legacyState.setUserCardId(cardId);
        legacyState.setAlgorithmId("fsrs_v6");
        JsonNode legacyJson = MAPPER.createObjectNode().put("legacy", true);
        legacyState.setState(legacyJson);
        legacyState.setReviewCount(2);
        legacyState.setLastReviewAt(Instant.now().minusSeconds(3600));
        when(stateRepo.findByIdForUpdate(cardId)).thenReturn(Optional.of(legacyState));
        when(stateRepo.save(any(SrCardStateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CanonicalProgress canonicalProgress = new CanonicalProgress(0.4, 5.0);
        when(legacyAlgorithm.toCanonical(legacyJson)).thenReturn(canonicalProgress);
        JsonNode convertedState = MAPPER.createObjectNode().put("converted", true);
        when(newAlgorithm.fromCanonical(canonicalProgress, effectiveCfg)).thenReturn(convertedState);

        JsonNode newState = MAPPER.createObjectNode().put("new", true);
        Instant now = Instant.now();
        SrsAlgorithm.ReviewComputation computation = new SrsAlgorithm.ReviewComputation(
                newState,
                now.plusSeconds(600),
                now,
                1
        );
        when(newAlgorithm.apply(any(), eq(Rating.GOOD), any(), eq(effectiveCfg))).thenReturn(computation);

        PreferencesSnapshot snapshot = new PreferencesSnapshot(
                deckId,
                Duration.ofMinutes(10),
                20,
                null,
                0,
                0
        );
        when(preferencesService.getSnapshot(eq(deckId), any())).thenReturn(snapshot);
        when(userCardRepo.countDue(eq(userId), eq(deckId), any())).thenReturn(0L);
        when(userCardRepo.countNew(userId, deckId)).thenReturn(0L);
        when(userCardRepo.findDueCardIds(eq(userId), eq(deckId), any(), any())).thenReturn(List.of());
        lenient().when(userCardRepo.findNewCardIds(eq(userId), eq(deckId), any())).thenReturn(List.of());
        reviewService.answer(userId, deckId, cardId, Rating.GOOD);

        ArgumentCaptor<SrsAlgorithm.ReviewInput> inputCaptor = ArgumentCaptor.forClass(SrsAlgorithm.ReviewInput.class);
        verify(newAlgorithm).apply(inputCaptor.capture(), eq(Rating.GOOD), any(), eq(effectiveCfg));
        assertThat(inputCaptor.getValue().state()).isEqualTo(convertedState);
        verify(preferencesService).incrementCounters(eq(deckId), eq(false), any());
    }
}
