package app.mnema.core.review.controller;

import app.mnema.core.review.controller.dto.AnswerCardRequest;
import app.mnema.core.review.controller.dto.ReviewAnswerResponse;
import app.mnema.core.review.controller.dto.ReviewDeckAlgorithmResponse;
import app.mnema.core.review.controller.dto.ReviewNextCardResponse;
import app.mnema.core.review.controller.dto.ReviewPreferencesDto;
import app.mnema.core.review.controller.dto.SeedCardProgressRequest;
import app.mnema.core.review.controller.dto.UpdateAlgorithmRequest;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.domain.ReviewSource;
import app.mnema.core.review.service.ReviewService;
import app.mnema.core.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void controllerDelegatesToReviewServiceWithResolvedUserId() {
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        ReviewService reviewService = mock(ReviewService.class);
        ReviewController controller = new ReviewController(currentUserProvider, reviewService);
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", userId.toString()).build();

        ReviewNextCardResponse next = new ReviewNextCardResponse(
                deckId, "fsrs_v6", cardId, null, true, MAPPER.createObjectNode(),
                Map.of(Rating.GOOD, new ReviewNextCardResponse.IntervalPreview(Instant.now(), "1d")),
                Instant.now(), true,
                new ReviewNextCardResponse.QueueSummary(1, 2, 3, 4, 5, 6)
        );
        ReviewDeckAlgorithmResponse algorithm = new ReviewDeckAlgorithmResponse(
                deckId, "fsrs_v6", MAPPER.createObjectNode(), MAPPER.createObjectNode(),
                new ReviewPreferencesDto(10, 24, 200, 4, "UTC"), 10, 11, 12
        );
        ReviewAnswerResponse answer = new ReviewAnswerResponse(cardId, Rating.GOOD, Instant.now(), next, null);
        when(currentUserProvider.getUserId(jwt)).thenReturn(userId);
        when(reviewService.nextCard(userId, deckId)).thenReturn(next);
        when(reviewService.getDeckAlgorithm(userId, deckId)).thenReturn(algorithm);
        when(reviewService.updateDeckAlgorithm(userId, deckId, "hlr", MAPPER.createObjectNode(), null)).thenReturn(algorithm);
        when(reviewService.answer(userId, deckId, cardId, Rating.GOOD, 1200, ReviewSource.web, MAPPER.createObjectNode())).thenReturn(answer);

        assertThat(controller.next(jwt, deckId)).isEqualTo(next);
        assertThat(controller.getAlgorithm(jwt, deckId)).isEqualTo(algorithm);
        assertThat(controller.updateAlgorithm(jwt, deckId, new UpdateAlgorithmRequest("hlr", MAPPER.createObjectNode(), null))).isEqualTo(algorithm);
        assertThat(controller.answer(jwt, deckId, cardId, new AnswerCardRequest("good", 1200, ReviewSource.web, MAPPER.createObjectNode()))).isEqualTo(answer);

        List<SeedCardProgressRequest> requests = List.of(
                new SeedCardProgressRequest(cardId, 0.4, 3.0, 3, Instant.now(), Instant.now().plusSeconds(60), false)
        );
        controller.seedProgress(jwt, deckId, requests);
        verify(reviewService).seedProgress(userId, deckId, requests);
    }
}
