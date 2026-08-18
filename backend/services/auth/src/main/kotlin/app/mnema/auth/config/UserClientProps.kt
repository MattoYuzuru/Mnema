package app.mnema.auth.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.user")
data class UserClientProps(
    val baseUrl: String = "http://localhost:8084/api/user",
    @field:NotBlank val internalToken: String = ""
)
