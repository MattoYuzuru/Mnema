package app.mnema.learning.library;

import app.mnema.learning.platform.api.ApiSecurityErrors;
import app.mnema.learning.platform.api.RateLimitedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Admits a signed-in viewer of the public chain BEFORE Identity is asked about the token ({@code CurrentIdentityFilter} makes one {@code /userinfo}
 * round trip per request): the subject comes from the already validated JWT, so a single token cannot drive unlimited Identity calls whatever the
 * route does afterwards. A guest has no token and is counted by the service. A refused request is the {@code 429 RATE_LIMITED} problem written here,
 * with {@code Retry-After}. Not a bean: it belongs to the one security chain that builds it, never to the servlet container's global chain.
 */
public final class PublicAccountLimitFilter extends OncePerRequestFilter {
    private final PublicReadLimiter limiter;
    private final ApiSecurityErrors errors;

    public PublicAccountLimitFilter(PublicReadLimiter limiter, ApiSecurityErrors errors) {
        this.limiter = limiter;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
            try {
                limiter.admit(Viewer.account(UUID.fromString(authentication.getName()), null));
            } catch (RateLimitedException refused) {
                errors.rateLimited(request, response, refused.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
