package app.mnema.user.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

class InternalTokenAuthFilterTest {

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `matching internal bearer token creates authenticated principal`() {
        val filter = InternalTokenAuthFilter(UserInternalAuthProps("internal-secret"))
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer internal-secret")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val authentication = SecurityContextHolder.getContext().authentication
        assertEquals("internal-secret", (authentication.principal as Jwt).tokenValue)
        assertTrue(authentication.authorities.any { it.authority == "SCOPE_user.internal" })
    }

    @Test
    fun `filter keeps existing authentication untouched`() {
        val existing = TestingAuthenticationToken("user", "password", "ROLE_USER").apply { isAuthenticated = true }
        SecurityContextHolder.getContext().authentication = existing
        val filter = InternalTokenAuthFilter(UserInternalAuthProps("internal-secret"))
        val request = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer internal-secret")
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertEquals(existing, SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `filter ignores blank config missing header and wrong token`() {
        val noTokenFilter = InternalTokenAuthFilter(UserInternalAuthProps(""))
        noTokenFilter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)

        val configuredFilter = InternalTokenAuthFilter(UserInternalAuthProps("internal-secret"))
        configuredFilter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)

        val wrongHeaderRequest = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer something-else")
        }
        configuredFilter.doFilter(wrongHeaderRequest, MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)

        val malformedRequest = MockHttpServletRequest().apply {
            addHeader("Authorization", "Basic abc")
        }
        configuredFilter.doFilter(malformedRequest, MockHttpServletResponse(), MockFilterChain())
        assertNull(SecurityContextHolder.getContext().authentication)
    }
}
