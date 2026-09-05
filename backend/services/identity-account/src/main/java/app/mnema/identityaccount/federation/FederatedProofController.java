package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.BrowserSessions;
import app.mnema.identityaccount.security.OwnershipProofs;
import app.mnema.identityaccount.security.RateLimits;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts/me/proofs")
public class FederatedProofController {
    public record Request(@NotBlank String provider, @NotNull OwnershipProofs.Purpose purpose) {
    }

    private final ClientRegistrationRepository registrations;
    private final Clock clock;
    private final RateLimits limits;

    public FederatedProofController(ClientRegistrationRepository registrations, Clock clock, RateLimits limits) {
        this.registrations = registrations;
        this.clock = clock;
        this.limits = limits;
    }

    /**
     * The state-bound callback returns the same one-use proof JSON as local reauthentication.
     */
    @PostMapping("/federated")
    Map<String, String> start(Authentication auth, @Valid @RequestBody Request r, HttpServletRequest request) {
        var access = BrowserSessions.access(auth);
        if ((r.purpose() == OwnershipProofs.Purpose.RESET_PASSWORD ||
                r.purpose() == OwnershipProofs.Purpose.VERIFY_EMAIL) ||
                registrations.findByRegistrationId(r.provider()) == null)
            throw AccountFailure.forbidden();
        if (!limits.allow("proof", access.accountId().toString(), 10)) throw new AccountFailure(429, "try_later");
        request.getSession().setAttribute("identity.intent", new ArrayList<>(
                List.of(access.accountId().toString(), Long.toString(access.generation()), r.provider(),
                        Long.toString(clock.instant().plusSeconds(300).getEpochSecond()), r.purpose().name(),
                        "proof")));
        return Map.of("authorizationUrl", "/oauth2/authorization/" + r.provider());
    }
}
