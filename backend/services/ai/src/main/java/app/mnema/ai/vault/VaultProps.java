package app.mnema.ai.vault;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.vault")
public record VaultProps(
        String masterKey,
        String keyId
) {
}
