package app.mnema.media.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalTokenAuthFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesWhenBearerMatchesInternalToken() throws ServletException, IOException {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(new MediaInternalAuthProps("internal-secret"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer internal-secret");

        filter.doFilter(request, new MockHttpServletResponse(), passthroughChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("SCOPE_media.internal", authentication.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void leavesContextEmptyWhenTokenDoesNotMatch() throws ServletException, IOException {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(new MediaInternalAuthProps("internal-secret"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer wrong-token");

        filter.doFilter(request, new MockHttpServletResponse(), passthroughChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private FilterChain passthroughChain() {
        return (request, response) -> {
        };
    }
}
