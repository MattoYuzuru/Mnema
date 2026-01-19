package app.mnema.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "turnstile")
data class TurnstileProps(
    val siteKey: String = "",
    val secretKey: String = ""
) {
    fun enabled(): Boolean = siteKey.isNotBlank() && secretKey.isNotBlank()
}
