package app.mnema.ai.provider.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.anthropic")
public record ClaudeProps(
        String baseUrl,
        String apiVersion,
        String defaultModel,
        Integer defaultMaxTokens
){
}
