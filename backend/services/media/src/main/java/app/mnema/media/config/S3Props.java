package app.mnema.media.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.s3")
public record S3Props(
        @NotBlank String bucket,
        @NotBlank String region,
        @NotBlank String endpoint,
        boolean pathStyleAccess,
        @NotBlank String accessKey,
        @NotBlank String secretKey
) {
}
