package app.mnema.learning.usage;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The paywall's catalogue and the owner's current entitlement. Read-only and parameterless: a query parameter, a header
 * or a return URL cannot select or change a plan; only {@link EntitlementInbox#accept} can.
 */
@RestController
@RequestMapping(value = "/plans", produces = MediaType.APPLICATION_JSON_VALUE)
public final class PlansController {
    private final PlansService plans;

    PlansController(PlansService plans) {
        this.plans = plans;
    }

    @GetMapping
    ResponseEntity<PlansView> read(@AuthenticationPrincipal Jwt identity) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(plans.read(UsageController.owner(identity)));
    }
}
