package app.mnema.identityaccount.deletion;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.BrowserSessions;
import app.mnema.identityaccount.security.ClientAddresses;
import app.mnema.identityaccount.security.OwnershipProofs;
import app.mnema.identityaccount.security.RateLimits;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/deletion")
public class AccountDeletionController {
    public record Federated(@NotBlank String provider) {
    }

    public record PasswordProof(@NotBlank @Size(max = 320) String login,
                                @NotBlank @Size(max = 128) String password) {
    }

    public record Confirmed(@NotBlank @Size(max = 128) String proof) {
    }

    public record Cancelled(boolean ordinaryAccessRestored) {
    }

    private final AccountDeletions deletions;
    private final AccountStore accounts;
    private final BrowserSessions sessions;
    private final ClientRegistrationRepository registrations;
    private final AccountDeletionPolicy policy;
    private final RateLimits limits;
    private final Clock clock;
    private final ClientAddresses clientAddresses;

    public AccountDeletionController(AccountDeletions deletions, AccountStore accounts, BrowserSessions sessions,
                                     ClientRegistrationRepository registrations, AccountDeletionPolicy policy,
                                     RateLimits limits, Clock clock, ClientAddresses clientAddresses) {
        this.deletions = deletions;
        this.accounts = accounts;
        this.sessions = sessions;
        this.registrations = registrations;
        this.policy = policy;
        this.limits = limits;
        this.clock = clock;
        this.clientAddresses = clientAddresses;
    }

    @GetMapping("/recovery/{operationId}")
    AccountDeletionView status(Authentication authentication, @PathVariable UUID operationId) {
        return deletions.recovery(BrowserSessions.access(authentication), operationId);
    }

    @DeleteMapping("/recovery/{operationId}")
    Cancelled cancel(Authentication authentication, @PathVariable UUID operationId, HttpServletRequest request,
                     HttpServletResponse response) {
        var access = deletions.cancel(BrowserSessions.access(authentication), operationId);
        boolean restored = accounts.get(access.accountId(), false).status().equals("ACTIVE");
        sessions.clear(request);
        if (restored) sessions.login(access, request, response);
        return new Cancelled(restored);
    }

    @PostMapping("/recovery/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        sessions.clear(request);
    }

    @PostMapping("/recovery/federated")
    Map<String, String> federated(@Valid @RequestBody Federated federated, HttpServletRequest request) {
        return federatedIntent(federated.provider(), "deletion-recovery", request);
    }

    @PostMapping("/proof/password")
    OwnershipProofs.Proof passwordProof(@Valid @RequestBody PasswordProof proof, HttpServletRequest request,
                                        HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return deletions.passwordProof(proof.login(), proof.password(), clientAddresses.resolve(request));
    }

    @PostMapping("/proof/federated")
    Map<String, String> federatedProof(@Valid @RequestBody Federated federated, HttpServletRequest request) {
        return federatedIntent(federated.provider(), "deletion-proof", request);
    }

    @PostMapping("/confirmed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AccountDeletionView confirmed(@Valid @RequestBody Confirmed confirmed, HttpServletRequest request) {
        var result = deletions.requestConfirmed(confirmed.proof());
        sessions.clear(request);
        return result;
    }

    private Map<String, String> federatedIntent(String provider, String purpose, HttpServletRequest request) {
        policy.requireEnabled();
        if (registrations.findByRegistrationId(provider) == null)
            throw AccountFailure.forbidden();
        if (!limits.allow("deletion-recovery", clientAddresses.resolve(request), 20))
            throw new AccountFailure(429, "try_later");
        request.getSession().setAttribute("identity.intent", new ArrayList<>(List.of(
                "", "", provider, Long.toString(clock.instant().plusSeconds(300).getEpochSecond()),
                "DELETE_ACCOUNT", purpose)));
        return Map.of("authorizationUrl", "/oauth2/authorization/" + provider);
    }
}
