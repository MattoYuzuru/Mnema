package app.mnema.user.media.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.media")
data class MediaClientProps(
    val baseUrl: String,
    val internalToken: String? = null
)
