package app.mnema.ai.provider.grok;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.grok")
public record GrokProps(
        String baseUrl,
        String defaultModel,
        String defaultTtsModel,
        String defaultVoice,
        String defaultTtsFormat,
        String defaultSttModel,
        String defaultImageModel,
        String defaultImageSize,
        String defaultImageQuality,
        String defaultImageStyle,
        String defaultImageFormat,
        String defaultVideoModel,
        Integer defaultVideoDurationSeconds,
        String defaultVideoResolution,
        Integer ttsRequestsPerMinute,
        Integer ttsMaxRetries,
        Long ttsRetryInitialDelayMs,
        Long ttsRetryMaxDelayMs
) {
}
