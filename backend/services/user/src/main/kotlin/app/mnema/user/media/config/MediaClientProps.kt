package app.mnema.user.media.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.media")
data class MediaClientProps(
    val baseUrl: String,
    @field:NotBlank val internalToken: String? = null
)
