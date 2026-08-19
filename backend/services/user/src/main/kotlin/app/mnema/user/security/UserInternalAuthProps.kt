package app.mnema.user.security

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "app.user")
data class UserInternalAuthProps(
    @field:NotBlank val internalToken: String = ""
)
