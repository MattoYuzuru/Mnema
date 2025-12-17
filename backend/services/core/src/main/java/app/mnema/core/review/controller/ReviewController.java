package app.mnema.core.review.controller;

import app.mnema.core.review.controller.dto.AnswerCardRequest;
import app.mnema.core.review.domain.Rating;
import app.mnema.core.review.service.ReviewService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/review")
public class ReviewController {

    private final CurrentUserProvider currentUserProvider;
    private final ReviewService reviewService;

    public ReviewController(CurrentUserProvider currentUserProvider, ReviewService reviewService) {
        this.currentUserProvider = currentUserProvider;
        this.reviewService = reviewService;
    }

    // GET /api/core/review/decks/{userDeckId}/next?limit=20
    @GetMapping("/decks/{userDeckId}/next")
    public List<?> next(@AuthenticationPrincipal Jwt jwt,
                        @PathVariable UUID userDeckId,
                        @RequestParam(defaultValue = "20") int limit) {
        UUID userId = currentUserProvider.getUserId(jwt);
        return reviewService.next(userId, userDeckId, limit);
    }

    // POST /api/core/review/cards/{userCardId}/answer
    @PostMapping("/cards/{userCardId}/answer")
    public void answer(@AuthenticationPrincipal Jwt jwt,
                       @PathVariable UUID userCardId,
                       @RequestBody AnswerCardRequest req) {
        UUID userId = currentUserProvider.getUserId(jwt);
        Rating rating = Rating.fromString(req.rating());
        reviewService.answer(userId, userCardId, rating);
    }
}
