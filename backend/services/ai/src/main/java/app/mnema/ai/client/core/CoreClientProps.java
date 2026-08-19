package app.mnema.ai.client.core;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.core")
public record CoreClientProps(
        String baseUrl,
        @NotBlank String internalToken
) {
}
