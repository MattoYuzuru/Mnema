package app.mnema.ai.provider.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.gemini")
public record GeminiProps(
        String baseUrl,
        String defaultModel,
        String defaultTtsModel,
        String defaultVoice,
        String defaultTtsMimeType
) {
}
