package app.mnema.core.user.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.user")
public record UserClientProps(
        String baseUrl,
        @NotBlank String internalToken
) {
}
