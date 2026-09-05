package app.mnema.identityaccount.security;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class BrowserSessions {
    private final AccountStore accounts;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BrowserSessions(AccountStore accounts, TransactionTemplate transactions, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
    }

    public static AccountAccess access(Authentication authentication) {
        try {
            if (authentication instanceof JwtAuthenticationToken token)
                return new AccountAccess(UUID.fromString(token.getName()),
                        Long.parseLong(token.getToken().getClaimAsString("generation")));
            return new AccountAccess(UUID.fromString(authentication.getName()),
                    Long.parseLong((String) authentication.getDetails()));
        } catch (RuntimeException error) {
            throw AccountFailure.denied();
        }
    }

    public void login(AccountAccess access, HttpServletRequest request, HttpServletResponse response) {
        transactions.executeWithoutResult(s -> {
            accounts.require(access, true);
            request.getSession();
            request.changeSessionId();
            request.getSession().removeAttribute("identity.intent");
            new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
            request.getSession()
                    .setAttribute("identity.session-expires", clock.instant().plusSeconds(28800).getEpochSecond());
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    User.withUsername(access.accountId().toString()).password("").authorities("ACCOUNT").build(), null,
                    List.of(new SimpleGrantedAuthority("ACCOUNT")));
            authentication.setDetails(Long.toString(access.generation()));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        });
    }

    public void logout(AccountAccess access, HttpServletRequest request) {
        transactions.executeWithoutResult(s -> {
            accounts.require(access, true);
            accounts.revoke(access.accountId());
        });
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
