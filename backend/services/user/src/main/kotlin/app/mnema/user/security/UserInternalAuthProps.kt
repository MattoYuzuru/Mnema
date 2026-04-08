package app.mnema.user.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.user")
data class UserInternalAuthProps(
    val internalToken: String = ""
)
