package app.mnema.identityaccount.security;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountErrors;
import app.mnema.identityaccount.contract.AccountFailure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

public final class CurrentAccountFilter extends OncePerRequestFilter {
    private final AccountStore accounts;
    private final Clock clock;
    private final AccountErrors errors;

    public CurrentAccountFilter(AccountStore accounts, Clock clock, AccountErrors errors) {
        this.accounts = accounts;
        this.clock = clock;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken) &&
                !(auth instanceof OAuth2ClientAuthenticationToken)) {
            try {
                accounts.require(BrowserSessions.access(auth), false);
                if (!(auth instanceof JwtAuthenticationToken)) {
                    var session = request.getSession(false);
                    if (session == null ||
                            !(session.getAttribute("identity.session-expires") instanceof Long expires) ||
                            clock.instant().getEpochSecond() >= expires)
                        throw AccountFailure.denied();
                }
            } catch (AccountFailure e) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                errors.write(response, 401, "authentication_failed");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
