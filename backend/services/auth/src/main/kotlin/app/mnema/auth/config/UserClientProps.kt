package app.mnema.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.user")
data class UserClientProps(
    val baseUrl: String = "http://localhost:8084/api/user",
    val internalToken: String = ""
)
