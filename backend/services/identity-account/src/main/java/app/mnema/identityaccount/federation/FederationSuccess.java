package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.contract.IssuerContract;
import app.mnema.identityaccount.deletion.AccountDeletions;
import app.mnema.identityaccount.security.BrowserSessions;
import app.mnema.identityaccount.security.OwnershipProofs.Purpose;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class FederationSuccess implements AuthenticationSuccessHandler {
    private final FederatedAccounts accounts;
    private final BrowserSessions sessions;
    private final Clock clock;
    private final String origin;
    private final AccountDeletions deletions;

    public FederationSuccess(FederatedAccounts accounts, BrowserSessions sessions, Clock clock,
                             AccountDeletions deletions, @Value("${identity.frontend-origin}") String origin) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.clock = clock;
        this.origin = new IssuerContract(URI.create(origin)).issuer();
        this.deletions = deletions;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        var oauth = (OAuth2AuthenticationToken) authentication;
        String provider = oauth.getAuthorizedClientRegistrationId();
        var session = request.getSession(false);
        Object intent = request.getAttribute("identity.intent");
        try {
            AccountAccess link = null;
            if (intent instanceof List<?> values) {
                if (values.size() != 6 || !provider.equals(values.get(2)) ||
                        clock.instant().getEpochSecond() >= Long.parseLong((String) values.get(3)))
                    throw AccountFailure.forbidden();
                if ("deletion-recovery".equals(values.get(5))) {
                    var recovery = accounts.recovery(ProviderUsers.external(provider, oauth.getPrincipal()));
                    deletions.passwordRecovery(recovery);
                    sessions.recovery(recovery, deletions.recoverySessionSeconds(), request, response);
                    response.sendRedirect(origin + "/account-deletion/recovery");
                    return;
                }
                if ("deletion-proof".equals(values.get(5))) {
                    var proof = accounts.deletionProof(ProviderUsers.external(provider, oauth.getPrincipal()));
                    sessions.clear(request);
                    response.setContentType("application/json");
                    response.setHeader("Cache-Control", "no-store");
                    response.getWriter()
                            .write("{\"token\":\"" + proof.token() + "\",\"expiresAt\":\"" +
                                    proof.expiresAt() + "\"}");
                    return;
                }
                link = new AccountAccess(UUID.fromString((String) values.get(0)),
                        Long.parseLong((String) values.get(1)));
                if ("proof".equals(values.get(5))) {
                    var proof = accounts.reauthenticate(ProviderUsers.external(provider, oauth.getPrincipal()), link,
                            Purpose.valueOf((String) values.get(4)));
                    sessions.login(link, request, response);
                    response.setContentType("application/json");
                    response.setHeader("Cache-Control", "no-store");
                    response.getWriter()
                            .write("{\"token\":\"" + proof.token() + "\",\"expiresAt\":\"" + proof.expiresAt() + "\"}");
                    return;
                }
            }
            var access = accounts.complete(ProviderUsers.external(provider, oauth.getPrincipal()), link);
            sessions.login(access, request, response);
            response.sendRedirect(origin + "/auth/callback");
        } catch (RuntimeException error) {
            SecurityContextHolder.clearContext();
            if (session != null) session.invalidate();
            response.sendRedirect(origin + "/auth/callback?error=federation_failed");
        }
    }
}
