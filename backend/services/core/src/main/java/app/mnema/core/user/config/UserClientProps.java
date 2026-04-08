package app.mnema.core.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.user")
public record UserClientProps(
        String baseUrl,
        String internalToken
) {
}
