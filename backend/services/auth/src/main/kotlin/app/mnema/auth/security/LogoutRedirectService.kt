package app.mnema.auth.security

import app.mnema.auth.config.CorsProps
import java.net.URI
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LogoutRedirectService(
    private val corsProps: CorsProps,
    @Value("\${auth.logout.default-redirect:https://mnema.app/}")
    private val defaultRedirect: String
) {
    fun resolve(redirectParam: String?): String {
        if (redirectParam.isNullOrBlank()) {
            return defaultRedirect
        }
        val candidate = redirectParam.trim()
        if (isSafeRelative(candidate)) {
            return candidate
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return defaultRedirect
        val origin = normalizeOrigin(uri) ?: return defaultRedirect
        return if (allowedOrigins().contains(origin)) candidate else defaultRedirect
    }

    private fun isSafeRelative(value: String): Boolean {
        return value.startsWith("/") && !value.startsWith("//")
    }

    private fun allowedOrigins(): Set<String> {
        val origins = corsProps.origins.ifEmpty { listOf(defaultRedirect) }
        return origins.mapNotNull { runCatching { URI(it) }.getOrNull() }
            .mapNotNull { normalizeOrigin(it) }
            .toSet()
    }

    private fun normalizeOrigin(uri: URI): String? {
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${scheme}://${host}${port}"
    }
}
