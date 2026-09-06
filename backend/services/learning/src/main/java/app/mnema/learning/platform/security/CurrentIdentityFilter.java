package app.mnema.learning.platform.security;

import app.mnema.learning.platform.api.ApiSecurityErrors;
import app.mnema.learning.platform.json.ContentJsonReader;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/** A successful check authorizes this request only; there is no positive-result cache. */
final class CurrentIdentityFilter extends OncePerRequestFilter {
    private final IdentityHttp http;
    private final URI userInfo;
    private final ApiSecurityErrors errors;
    private final ContentJsonReader json = new ContentJsonReader(16_384, 16, 2_048);

    CurrentIdentityFilter(IdentityHttp http, URI userInfo, ApiSecurityErrors errors) {
        this.http = http;
        this.userInfo = userInfo;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
            try {
                var result = http.get(userInfo, authentication.getToken().getTokenValue(), 16_384);
                if (result.statusCode() == 401 || result.statusCode() == 403) {
                    errors.unauthorized(request, response);
                    return;
                }
                if (result.statusCode() != 200 || !authentication.getName().equals(
                        json.read(result.body()).path("sub").textValue())) {
                    errors.unavailable(request, response);
                    return;
                }
            } catch (IOException | IllegalArgumentException exception) {
                errors.unavailable(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
