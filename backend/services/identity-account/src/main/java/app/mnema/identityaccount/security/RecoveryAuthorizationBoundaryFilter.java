package app.mnema.identityaccount.security;

import app.mnema.identityaccount.contract.AccountErrors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Blocks a recovery-only browser before any OAuth/OIDC endpoint can parse or mint a protocol artifact. */
public final class RecoveryAuthorizationBoundaryFilter extends OncePerRequestFilter {
    private final AccountErrors errors;

    public RecoveryAuthorizationBoundaryFilter(AccountErrors errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ACCOUNT_RECOVERY"))) {
            errors.write(response, 403, "operation_denied");
            return;
        }
        chain.doFilter(request, response);
    }
}
