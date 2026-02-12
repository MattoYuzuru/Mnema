package app.mnema.ai.provider.qwen;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.qwen")
public record QwenProps(
        String baseUrl,
        String dashscopeBaseUrl,
        String defaultModel,
        String defaultVisionModel,
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
