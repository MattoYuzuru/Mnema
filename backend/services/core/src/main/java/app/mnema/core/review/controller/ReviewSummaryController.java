package app.mnema.core.review.controller;

import app.mnema.core.review.controller.dto.ReviewSummaryResponse;
import app.mnema.core.review.service.ReviewService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/review")
public class ReviewSummaryController {

    private final CurrentUserProvider currentUserProvider;
    private final ReviewService reviewService;

    public ReviewSummaryController(CurrentUserProvider currentUserProvider, ReviewService reviewService) {
        this.currentUserProvider = currentUserProvider;
        this.reviewService = reviewService;
    }

    @GetMapping("/summary")
    public ReviewSummaryResponse getSummary(@AuthenticationPrincipal Jwt jwt) {
        var userId = currentUserProvider.getUserId(jwt);
        return reviewService.summary(userId);
    }
}
