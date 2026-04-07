package app.mnema.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.auth.features")
data class AuthFeaturesProps(
    val federatedEnabled: Boolean = true,
    val requireEmailVerification: Boolean = true
)
