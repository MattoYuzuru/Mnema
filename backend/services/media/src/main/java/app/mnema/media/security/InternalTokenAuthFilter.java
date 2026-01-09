package app.mnema.media.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class InternalTokenAuthFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final MediaInternalAuthProps props;

    public InternalTokenAuthFilter(MediaInternalAuthProps props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (hasAuthentication()) {
            filterChain.doFilter(request, response);
            return;
        }

        String internalToken = props.internalToken();
        if (internalToken == null || internalToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String auth = request.getHeader(AUTHORIZATION);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = auth.substring(BEARER_PREFIX.length()).trim();
        if (!internalToken.equals(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("scope", "media.internal")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .build();

        var authorities = List.of(new SimpleGrantedAuthority("SCOPE_media.internal"));
        AbstractAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities);
        authentication.setAuthenticated(true);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private boolean hasAuthentication() {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        return context != null && context.getAuthentication() != null;
    }
}
