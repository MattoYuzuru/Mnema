package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.BrowserSessions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
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
@RequestMapping("/api/accounts/me/identities")
public class FederationController {
    public record Link(@NotBlank String provider, @NotBlank @Size(max = 128) String proof) {
    }

    public record Unlink(@NotBlank @Size(max = 128) String proof) {
    }

    private final FederatedAccounts accounts;
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final Clock clock;

    public FederationController(FederatedAccounts accounts, ObjectProvider<ClientRegistrationRepository> registrations,
                                Clock clock) {
        this.accounts = accounts;
        this.registrations = registrations;
        this.clock = clock;
    }

    @GetMapping
    List<FederatedAccounts.Identity> list(Authentication a) {
        return accounts.identities(BrowserSessions.access(a));
    }

    @PostMapping("/link")
    Map<String, String> link(Authentication a, @Valid @RequestBody Link r, HttpServletRequest request) {
        var repo = registrations.getIfAvailable();
        if (repo == null || repo.findByRegistrationId(r.provider()) == null)
            throw new AccountFailure(400, "provider_unavailable");
        var access = BrowserSessions.access(a);
        accounts.authorizeLink(access, r.proof());
        request.getSession().setAttribute("identity.intent", new ArrayList<>(
                List.of(access.accountId().toString(), Long.toString(access.generation()), r.provider(),
                        Long.toString(clock.instant().plusSeconds(300).getEpochSecond()), "LINK_IDENTITY", "link")));
        return Map.of("authorizationUrl", "/oauth2/authorization/" + r.provider());
    }

    @DeleteMapping("/{identity}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(Authentication a, @PathVariable UUID identity, @Valid @RequestBody Unlink r) {
        accounts.unlink(BrowserSessions.access(a), identity, r.proof());
    }
}
