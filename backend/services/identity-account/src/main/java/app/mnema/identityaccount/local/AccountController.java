package app.mnema.identityaccount.local;

import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.deletion.AccountDeletionView;
import app.mnema.identityaccount.deletion.AccountDeletions;
import app.mnema.identityaccount.moderation.Moderation;
import app.mnema.identityaccount.profile.Profiles;
import app.mnema.identityaccount.security.BrowserSessions;
import app.mnema.identityaccount.security.ClientAddresses;
import app.mnema.identityaccount.security.OwnershipProofs;
import app.mnema.identityaccount.security.RateLimits;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    public record Registration(@NotBlank @Email @Size(max = 320) String email,
                               @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,50}") String loginName,
                               @NotBlank @Size(max = 128) String password,
                               @Pattern(regexp = "[A-Za-z0-9_.-]{3,50}") String profileUsername) {
    }

    public record Login(@NotBlank @Size(max = 320) String login, @NotBlank @Size(max = 128) String password) {
    }

    public record Edit(@NotNull @Pattern(regexp = "[A-Za-z0-9_.-]{3,50}") String profileUsername,
                       @NotNull @Size(max = 200) String displayName, @NotNull @Size(max = 200) String bio) {
    }

    public record Password(@NotBlank @Size(max = 128) String currentPassword,
                           @NotBlank @Size(max = 128) String newPassword) {
    }

    public record ProofRequest(@NotBlank @Size(max = 128) String password, @NotNull OwnershipProofs.Purpose purpose) {
    }

    public record Ban(@Size(max = 280) String reason) {
    }

    public record DeleteAccount(@NotBlank @Size(max = 128) String proof) {
    }

    private final LocalAccounts local;
    private final BrowserSessions sessions;
    private final Profiles profiles;
    private final OwnershipProofs proofs;
    private final TransactionTemplate transactions;
    private final RateLimits limits;
    private final Moderation moderation;
    private final ClientAddresses clientAddresses;
    private final AccountDeletions deletions;

    public AccountController(LocalAccounts local, BrowserSessions sessions, Profiles profiles, OwnershipProofs proofs,
                             TransactionTemplate transactions, RateLimits limits, Moderation moderation,
                             ClientAddresses clientAddresses, AccountDeletions deletions) {
        this.local = local;
        this.sessions = sessions;
        this.profiles = profiles;
        this.proofs = proofs;
        this.transactions = transactions;
        this.limits = limits;
        this.moderation = moderation;
        this.clientAddresses = clientAddresses;
        this.deletions = deletions;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Profiles.Profile register(@Valid @RequestBody Registration r, HttpServletRequest request) {
        return profiles.get(
                local.register(r.email(), r.loginName(), r.password(), r.profileUsername(), clientAddresses.resolve(request)));
    }

    @PostMapping("/login")
    Object login(@Valid @RequestBody Login r, HttpServletRequest request, HttpServletResponse response) {
        var authentication = local.authenticate(r.login(), r.password(), clientAddresses.resolve(request));
        if (authentication.recoveryOnly()) {
            var view = deletions.recovery(authentication.access(), null);
            sessions.recovery(authentication.access(), deletions.recoverySessionSeconds(), request, response);
            return view;
        }
        sessions.login(authentication.access(), request, response);
        return profiles.get(authentication.access());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(Authentication authentication, HttpServletRequest request) {
        sessions.logout(BrowserSessions.access(authentication), request);
    }

    @GetMapping({"/me", "/session"})
    Profiles.Profile me(Authentication authentication) {
        return profiles.get(BrowserSessions.access(authentication));
    }

    @PutMapping("/me")
    Profiles.Profile update(Authentication authentication, @Valid @RequestBody Edit edit) {
        return profiles.update(BrowserSessions.access(authentication), edit.profileUsername(), edit.displayName(),
                edit.bio());
    }

    @GetMapping("/profiles/{id}")
    Profiles.PublicProfile profile(@PathVariable UUID id) {
        return profiles.publicProfile(id);
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void password(Authentication authentication, @Valid @RequestBody Password r) {
        local.changePassword(BrowserSessions.access(authentication), r.currentPassword(), r.newPassword());
    }

    @PostMapping("/me/proofs")
    OwnershipProofs.Proof proof(Authentication authentication, @Valid @RequestBody ProofRequest r,
                                HttpServletRequest request, HttpServletResponse response) {
        var access = BrowserSessions.access(authentication);
        if ((r.purpose() == OwnershipProofs.Purpose.RESET_PASSWORD ||
                r.purpose() == OwnershipProofs.Purpose.VERIFY_EMAIL)) throw AccountFailure.forbidden();
        if (!limits.allow("proof", access.accountId().toString(), 10)) throw new AccountFailure(429, "try_later");
        var proof = transactions.execute(s -> {
            local.verifyPassword(access, r.password());
            return proofs.issue(access, r.purpose());
        });
        sessions.login(access, request, response);
        return proof;
    }

    @PostMapping("/me/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AccountDeletionView delete(Authentication authentication, @Valid @RequestBody DeleteAccount deletion,
                               HttpServletRequest request) {
        var view = deletions.request(BrowserSessions.access(authentication), deletion.proof());
        sessions.clear(request);
        return view;
    }

    @PostMapping("/admin/accounts/{id}/ban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void ban(Authentication a, @PathVariable UUID id, @Valid @RequestBody Ban r) {
        moderation.apply(BrowserSessions.access(a), id, Moderation.Action.BAN, r.reason());
    }

    @PostMapping("/admin/accounts/{id}/unban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unban(Authentication a, @PathVariable UUID id) {
        moderation.apply(BrowserSessions.access(a), id, Moderation.Action.UNBAN, null);
    }

    @PostMapping("/admin/accounts/{id}/admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void grant(Authentication a, @PathVariable UUID id) {
        moderation.apply(BrowserSessions.access(a), id, Moderation.Action.GRANT_ADMIN, null);
    }

    @DeleteMapping("/admin/accounts/{id}/admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Authentication a, @PathVariable UUID id) {
        moderation.apply(BrowserSessions.access(a), id, Moderation.Action.REVOKE_ADMIN, null);
    }
}
