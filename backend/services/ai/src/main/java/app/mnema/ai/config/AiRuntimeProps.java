package app.mnema.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.runtime")
public record AiRuntimeProps(
        boolean systemManagedProviderEnabled,
        String systemProviderName,
        boolean ollamaEnabled,
        String ollamaBaseUrl
) {
}
