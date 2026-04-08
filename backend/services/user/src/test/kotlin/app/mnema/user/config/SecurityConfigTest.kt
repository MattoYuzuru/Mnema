package app.mnema.user.config

import app.mnema.user.security.InternalTokenAuthFilter
import app.mnema.user.security.UserInternalAuthProps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest

class SecurityConfigTest {

    private val config = SecurityConfig(MockEnvironment())

    @Test
    fun `bearer token resolver skips internal token and returns external bearer`() {
        val resolver = config.bearerTokenResolver(UserInternalAuthProps("internal-secret"))

        val internalRequest = MockHttpServletRequest().apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer internal-secret")
        }
        val externalRequest = MockHttpServletRequest().apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
        }
        val missingRequest = MockHttpServletRequest()

        assertNull(resolver.resolve(internalRequest))
        assertEquals("user-jwt", resolver.resolve(externalRequest))
        assertNull(resolver.resolve(missingRequest))
    }

    @Test
    fun `internal token auth filter bean uses provided props`() {
        val filter = config.internalTokenAuthFilter(UserInternalAuthProps("internal-secret"))

        assertEquals(InternalTokenAuthFilter::class, filter::class)
    }
}
