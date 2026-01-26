package app.mnema.ai.client.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.media")
public record MediaClientProps(
        String baseUrl,
        String internalToken
) {
}
