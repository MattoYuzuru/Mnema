package app.mnema.auth.config

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * A narrowly scoped Turnstile bypass for the dedicated release-smoke account.
 * Registration/login rate limits, validation, password checks, account locks and moderation remain active.
 */
@ConfigurationProperties(prefix = "auth.smoke")
data class SmokeAuthProps(
    val login: String = "",
    val turnstileBypassKey: String = ""
) {
    init {
        require(login.isBlank() == turnstileBypassKey.isBlank()) {
            "auth.smoke.login and auth.smoke.turnstile-bypass-key must be configured together"
        }
        require(login.isBlank() || login == login.trim()) {
            "auth.smoke.login must not contain surrounding whitespace"
        }
        require(turnstileBypassKey.isBlank() || turnstileBypassKey.length >= MIN_KEY_LENGTH) {
            "auth.smoke.turnstile-bypass-key must contain at least $MIN_KEY_LENGTH characters"
        }
    }

    fun allows(candidateLogin: String, candidateKey: String?): Boolean {
        if (login.isBlank() || candidateKey.isNullOrBlank()) return false
        if (!login.equals(candidateLogin.trim(), ignoreCase = true)) return false

        return MessageDigest.isEqual(
            sha256(turnstileBypassKey),
            sha256(candidateKey)
        )
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val MIN_KEY_LENGTH = 32
    }
}
