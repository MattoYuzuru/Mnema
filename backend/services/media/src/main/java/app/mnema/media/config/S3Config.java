package app.mnema.media.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(S3Props.class)
public class S3Config {

    @Bean
    public S3Client s3Client(S3Props p) {
        var creds = AwsBasicCredentials.create(p.accessKey(), p.secretKey());

        var s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(p.pathStyleAccess()) // true для MinIO часто нужно
                .build();

        return S3Client.builder()
                .region(Region.of(p.region()))
                .endpointOverride(URI.create(p.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(s3Config)
                .build();
    }

    @Bean
    @Qualifier("publicS3Presigner")
    public S3Presigner publicS3Presigner(S3Props p) {
        var presignEndpoint = (p.publicEndpoint() != null && !p.publicEndpoint().isBlank())
                ? p.publicEndpoint()
                : p.endpoint();
        return buildPresigner(p, presignEndpoint);
    }

    @Bean
    @Qualifier("internalS3Presigner")
    public S3Presigner internalS3Presigner(S3Props p) {
        return buildPresigner(p, p.endpoint());
    }

    private S3Presigner buildPresigner(S3Props p, String endpoint) {
        var creds = AwsBasicCredentials.create(p.accessKey(), p.secretKey());
        var s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(p.pathStyleAccess())
                .build();

        return S3Presigner.builder()
                .region(Region.of(p.region()))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .serviceConfiguration(s3Config)
                .build();
    }
}
