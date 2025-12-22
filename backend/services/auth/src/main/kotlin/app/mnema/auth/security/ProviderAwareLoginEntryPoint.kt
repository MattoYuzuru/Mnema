package app.mnema.auth.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.Locale
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint

/**
 * Picks the upstream OAuth2 client (google/github/yandex) based on the `provider` query parameter,
 * falling back to Google for backwards compatibility.
 */
class ProviderAwareLoginEntryPoint(
    private val defaultProvider: String,
    supportedProviders: Collection<String>
) : LoginUrlAuthenticationEntryPoint("/oauth2/authorization/$defaultProvider") {

    private val allowed = supportedProviders.map { it.lowercase(Locale.ROOT) }.toSet()

    override fun determineUrlToUseForThisRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException?
    ): String {
        val requested = request.getParameter("provider")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { allowed.contains(it) }
        val provider = requested ?: defaultProvider.lowercase(Locale.ROOT)
        return "/oauth2/authorization/$provider"
    }
}
