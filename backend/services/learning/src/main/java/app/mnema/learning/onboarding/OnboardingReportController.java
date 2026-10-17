package app.mnema.learning.onboarding;

import app.mnema.learning.admin.AdminConsoleAccess;
import app.mnema.learning.admin.AdminReportRange;
import app.mnema.learning.usage.UsageClock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * {@code GET /api/admin/onboarding/experiment?from=&to=}, the owner console's holdout report (contract {@code contracts/onboarding/journey.json}, {@code report}).
 * Only the configured console owner on the admin client reaches it ({@link AdminConsoleAccess}, a 403 for anyone else before anything is read); the private chain
 * gives {@code learning.read} to GET. The window is the console report's: UTC dates, {@code to} exclusive, 1 to 90 days, {@code to} not after tomorrow, else 400;
 * an unknown or duplicate query parameter is 400. It does not depend on {@code learning.onboarding.enabled}: the owner can read the result after a rollback.
 */
@RestController
@RequestMapping(value = "/admin/onboarding", produces = MediaType.APPLICATION_JSON_VALUE)
public final class OnboardingReportController {
    private final AdminConsoleAccess access;
    private final OnboardingReport report;
    private final UsageClock clock;

    OnboardingReportController(AdminConsoleAccess access, OnboardingReport report, UsageClock clock) {
        this.access = access;
        this.report = report;
        this.clock = clock;
    }

    @GetMapping("/experiment")
    ResponseEntity<JsonNode> experiment(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        access.require(token);
        AdminReportRange.query(request, Set.of("from", "to"));
        AdminReportRange range = AdminReportRange.parse(request.getParameter("from"), request.getParameter("to"), clock.now());
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(headers).body(report.report(range));
    }
}
