package app.mnema.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.cors")
data class CorsProps(
    val origins: List<String> = emptyList()
)
