package app.mnema.importer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.media")
public record MediaClientProps(
        String baseUrl,
        String internalToken
) {
}
