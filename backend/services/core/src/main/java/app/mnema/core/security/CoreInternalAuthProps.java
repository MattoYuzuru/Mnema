package app.mnema.core.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.core")
public record CoreInternalAuthProps(
        @NotBlank String internalToken
) {
}
