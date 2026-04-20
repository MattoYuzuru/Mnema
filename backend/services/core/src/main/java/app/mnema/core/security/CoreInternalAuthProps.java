package app.mnema.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.core")
public record CoreInternalAuthProps(
        String internalToken
) {
}
