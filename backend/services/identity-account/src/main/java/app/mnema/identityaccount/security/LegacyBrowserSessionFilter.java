package app.mnema.identityaccount.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ends a browser session that was established before sessions carried the browser-session factor.
 *
 * <p>The authorization server derives the ID token's {@code auth_time} from that factor, so a session
 * without it could authorize but never complete a code exchange. Dropping it before any OAuth/OIDC
 * endpoint runs sends the user through the normal sign-in instead.
 */
public final class LegacyBrowserSessionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof UsernamePasswordAuthenticationToken && authentication.isAuthenticated()
                && !BrowserSessions.hasSessionFactor(authentication)) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
