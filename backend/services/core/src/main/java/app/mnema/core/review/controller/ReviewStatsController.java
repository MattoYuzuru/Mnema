package app.mnema.core.review.controller;

import app.mnema.core.review.controller.dto.ReviewStatsResponse;
import app.mnema.core.review.service.ReviewStatsService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/review/stats")
public class ReviewStatsController {

    private final CurrentUserProvider currentUserProvider;
    private final ReviewStatsService reviewStatsService;

    public ReviewStatsController(CurrentUserProvider currentUserProvider,
                                 ReviewStatsService reviewStatsService) {
        this.currentUserProvider = currentUserProvider;
        this.reviewStatsService = reviewStatsService;
    }

    @GetMapping
    public ReviewStatsResponse getStats(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(required = false) UUID userDeckId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) String timeZone,
                                        @RequestParam(required = false) Integer dayCutoffMinutes,
                                        @RequestParam(required = false) Integer forecastDays) {
        UUID userId = currentUserProvider.getUserId(jwt);
        return reviewStatsService.stats(userId, userDeckId, from, to, timeZone, dayCutoffMinutes, forecastDays);
    }
}
