package app.mnema.media.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class S3ConfigTest {

    @Test
    void pathStylePresignerKeepsBrowserUrlOnTheConfiguredOrigin() {
        var properties = new S3Props(
                "mnema-production",
                "ru-central1",
                "https://storage.yandexcloud.net",
                "https://storage.yandexcloud.net",
                true,
                "test-access-key",
                "test-secret-key"
        );
        var config = new S3Config();

        try (var presigner = config.publicS3Presigner(properties)) {
            var request = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key("media/example.png")
                    .build();
            var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .putObjectRequest(request)
                    .build());

            assertThat(presigned.url().getProtocol()).isEqualTo("https");
            assertThat(presigned.url().getHost()).isEqualTo("storage.yandexcloud.net");
            assertThat(presigned.url().getPath()).isEqualTo("/mnema-production/media/example.png");
        }
    }
}
