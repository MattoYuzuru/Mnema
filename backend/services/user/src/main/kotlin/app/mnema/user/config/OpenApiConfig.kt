package app.mnema.user.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.OAuthFlow
import io.swagger.v3.oas.annotations.security.OAuthFlows
import io.swagger.v3.oas.annotations.security.OAuthScope
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(title = "User API", version = "v1"),
    security = [SecurityRequirement(name = "oauth2")]
)
@SecurityScheme(
    name = "oauth2",
    type = SecuritySchemeType.OAUTH2,
    flows = OAuthFlows(
        authorizationCode = OAuthFlow(
            // TODO: add dev/prod envs
            authorizationUrl = "https://auth.mnema.app/oauth2/authorize", // http://localhost:8083/api/auth/oauth2/authorize
            tokenUrl = "https://auth.mnema.app/oauth2/token", // http://localhost:8083/api/auth/oauth2/token
            scopes = [
                OAuthScope(name = "user.read", description = "Read users"),
                OAuthScope(name = "user.write", description = "Write users"),
                OAuthScope(name = "openid", description = "OpenID scope")
            ]
        )
    )
)
class OpenApiConfig