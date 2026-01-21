package app.mnema.core.review.controller;

import app.mnema.core.review.controller.dto.AnswerCardRequest;
import app.mnema.core.review.controller.dto.ReviewAnswerResponse;
import app.mnema.core.review.controller.dto.ReviewDeckAlgorithmResponse;
import app.mnema.core.review.controller.dto.ReviewNextCardResponse;
import app.mnema.core.review.controller.dto.SeedCardProgressRequest;
import app.mnema.core.review.controller.dto.UpdateAlgorithmRequest;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.service.ReviewService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/review/decks")
public class ReviewController {

    private final CurrentUserProvider currentUserProvider;
    private final ReviewService reviewService;

    public ReviewController(CurrentUserProvider currentUserProvider, ReviewService reviewService) {
        this.currentUserProvider = currentUserProvider;
        this.reviewService = reviewService;
    }

    @GetMapping("/{userDeckId}/next")
    public ReviewNextCardResponse next(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID userDeckId) {
        UUID userId = currentUserProvider.getUserId(jwt);
        return reviewService.nextCard(userId, userDeckId);
    }

    @PostMapping("/{userDeckId}/cards/{userCardId}/answer")
    public ReviewAnswerResponse answer(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID userDeckId,
                                       @PathVariable UUID userCardId,
                                       @RequestBody AnswerCardRequest req) {
        UUID userId = currentUserProvider.getUserId(jwt);
        Rating rating = Rating.fromString(req.rating());
        return reviewService.answer(userId, userDeckId, userCardId, rating, req.responseMs(), req.source(), req.features());
    }

    @GetMapping("/{userDeckId}/algorithm")
    public ReviewDeckAlgorithmResponse getAlgorithm(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable UUID userDeckId) {
        UUID userId = currentUserProvider.getUserId(jwt);
        return reviewService.getDeckAlgorithm(userId, userDeckId);
    }

    @PutMapping("/{userDeckId}/algorithm")
    public ReviewDeckAlgorithmResponse updateAlgorithm(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable UUID userDeckId,
                                                       @RequestBody UpdateAlgorithmRequest req) {
        UUID userId = currentUserProvider.getUserId(jwt);
        return reviewService.updateDeckAlgorithm(
                userId,
                userDeckId,
                req.algorithmId(),
                req.algorithmParams(),
                req.reviewPreferences()
        );
    }

    @PostMapping("/{userDeckId}/states/import")
    public void seedProgress(@AuthenticationPrincipal Jwt jwt,
                             @PathVariable UUID userDeckId,
                             @RequestBody List<SeedCardProgressRequest> requests) {
        UUID userId = currentUserProvider.getUserId(jwt);
        reviewService.seedProgress(userId, userDeckId, requests);
    }
}
