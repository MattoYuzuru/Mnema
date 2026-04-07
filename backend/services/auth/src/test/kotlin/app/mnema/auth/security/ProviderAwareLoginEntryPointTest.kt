package app.mnema.auth.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.AuthenticationException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ProviderAwareLoginEntryPointTest {

    @Test
    fun `uses requested supported provider`() {
        val entryPoint = ProviderAwareLoginEntryPoint("google", listOf("google", "github", "yandex"))
        val request = MockHttpServletRequest().apply {
            setParameter("provider", "github")
        }

        val url = resolve(entryPoint, request)

        assertEquals("/oauth2/authorization/github", url)
    }

    @Test
    fun `falls back to default provider for unsupported request`() {
        val entryPoint = ProviderAwareLoginEntryPoint("google", listOf("google", "github", "yandex"))
        val request = MockHttpServletRequest().apply {
            setParameter("provider", "discord")
        }

        val url = resolve(entryPoint, request)

        assertEquals("/oauth2/authorization/google", url)
    }

    private fun resolve(entryPoint: ProviderAwareLoginEntryPoint, request: MockHttpServletRequest): String {
        val method = ProviderAwareLoginEntryPoint::class.java.getDeclaredMethod(
            "determineUrlToUseForThisRequest",
            jakarta.servlet.http.HttpServletRequest::class.java,
            jakarta.servlet.http.HttpServletResponse::class.java,
            AuthenticationException::class.java
        )
        method.isAccessible = true
        return method.invoke(entryPoint, request, MockHttpServletResponse(), null) as String
    }
}
