package app.mnema.core.config;

import app.mnema.core.security.CoreInternalAuthProps;
import app.mnema.core.security.InternalTokenAuthFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bearerTokenResolverIgnoresConfiguredInternalToken() {
        SecurityConfig config = new SecurityConfig();
        var resolver = config.bearerTokenResolver(new CoreInternalAuthProps("core-internal"));

        MockHttpServletRequest internalRequest = new MockHttpServletRequest();
        internalRequest.addHeader("Authorization", "Bearer core-internal");
        MockHttpServletRequest userRequest = new MockHttpServletRequest();
        userRequest.addHeader("Authorization", "Bearer user-token");
        MockHttpServletRequest missingHeaderRequest = new MockHttpServletRequest();

        assertThat(resolver.resolve(internalRequest)).isNull();
        assertThat(resolver.resolve(userRequest)).isEqualTo("user-token");
        assertThat(resolver.resolve(missingHeaderRequest)).isNull();
        assertThat(config.internalTokenAuthFilter(new CoreInternalAuthProps("core-internal")))
                .isInstanceOf(InternalTokenAuthFilter.class);
    }

    @Test
    void internalTokenAuthFilterAuthenticatesMatchingBearerToken() throws ServletException, IOException {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(new CoreInternalAuthProps("core-internal"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer core-internal");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(JwtAuthenticationToken.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .contains("SCOPE_core.internal");
    }

    @Test
    void internalTokenAuthFilterIgnoresMismatchedBearerToken() throws ServletException, IOException {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(new CoreInternalAuthProps("core-internal"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void internalTokenAuthFilterLeavesExistingAuthenticationUntouched() throws ServletException, IOException {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user", "pwd", "ROLE_USER"));
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(new CoreInternalAuthProps("core-internal"));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(TestingAuthenticationToken.class);
    }

    @Test
    void internalTokenAuthFilterSkipsWhenInternalTokenMissingOrHeaderAbsent() throws ServletException, IOException {
        InternalTokenAuthFilter missingTokenFilter = new InternalTokenAuthFilter(new CoreInternalAuthProps(""));
        InternalTokenAuthFilter missingHeaderFilter = new InternalTokenAuthFilter(new CoreInternalAuthProps("core-internal"));

        missingTokenFilter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        SecurityContextHolder.clearContext();
        missingHeaderFilter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
