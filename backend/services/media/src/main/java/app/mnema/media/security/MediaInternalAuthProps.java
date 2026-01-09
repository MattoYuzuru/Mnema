package app.mnema.media.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.media")
public record MediaInternalAuthProps(
        String internalToken
) {
}
