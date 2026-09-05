package app.mnema.identityaccount.avatar;

import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AvatarStorage implements AutoCloseable {
    private final S3Client s3;
    private final String bucket;
    private final boolean configured;

    public AvatarStorage(@Value("${identity.avatar.endpoint}") URI endpoint,
                         @Value("${identity.avatar.region}") String region,
                         @Value("${identity.avatar.bucket}") String bucket,
                         @Value("${identity.avatar.access-key}") String access,
                         @Value("${identity.avatar.secret-key}") String secret,
                         @Value("${identity.avatar.allow-loopback-http:false}") boolean loopback,
                         @Value("${identity.avatar.allow-staging-minio-http:false}") boolean stagingMinio) {
        boolean loopbackHttp = loopback && "http".equals(endpoint.getScheme()) &&
                Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost());
        boolean stagingMinioHttp = stagingMinio && endpoint.equals(URI.create("http://minio:9000"));
        if (!"https".equals(endpoint.getScheme()) && !loopbackHttp && !stagingMinioHttp)
            throw new IllegalArgumentException("Avatar endpoint requires HTTPS");
        this.bucket = bucket;
        configured = !access.isBlank() && !secret.isBlank();
        s3 = S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(configured ? access : "unconfigured",
                                configured ? secret : "unconfigured")))
                .overrideConfiguration(
                        c -> c.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                .build();
    }

    public boolean configured() {
        return configured;
    }

    public void put(String key, UUID account, UUID asset, AvatarImage image) {
        requireConfigured();
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(image.contentType())
                            .metadata(Map.of("account-id", account.toString(), "asset-id", asset.toString())).build(),
                    RequestBody.fromBytes(image.bytes()));
        } catch (RuntimeException e) {
            throw new AccountFailure(503, "avatar_storage_unavailable");
        }
    }

    public byte[] get(String key) {
        requireConfigured();
        try (var response = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            if (response.response().contentLength() > AvatarImage.MAX_BYTES)
                throw new AccountFailure(503, "avatar_storage_invalid");
            byte[] bytes = response.readNBytes(AvatarImage.MAX_BYTES + 1);
            if (bytes.length > AvatarImage.MAX_BYTES) throw new AccountFailure(503, "avatar_storage_invalid");
            return bytes;
        } catch (NoSuchKeyException e) {
            throw new AccountFailure(404, "avatar_not_found");
        } catch (Exception e) {
            if (e instanceof AccountFailure failure) throw failure;
            throw new AccountFailure(503, "avatar_storage_unavailable");
        }
    }

    public boolean delete(String key) {
        if (!configured) return false;
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void requireConfigured() {
        if (!configured) throw new AccountFailure(503, "avatar_storage_unavailable");
    }

    @Override
    public void close() {
        s3.close();
    }
}
