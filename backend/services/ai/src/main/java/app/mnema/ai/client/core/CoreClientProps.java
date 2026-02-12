package app.mnema.ai.client.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.core")
public record CoreClientProps(
        String baseUrl
) {
}
