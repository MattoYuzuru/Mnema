package app.mnema.identityaccount.avatar;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in parity check of the Identity avatar flow against the real Yandex Object Storage bucket (versioning on,
 * default SSE-KMS). Skipped unless {@code MNEMA_S3_LIVE=true}. Configuration comes only from the environment and is
 * never printed: {@code MNEMA_S3_LIVE_ENDPOINT} (default https://storage.yandexcloud.net),
 * {@code MNEMA_S3_LIVE_REGION} (default ru-central1), {@code MNEMA_S3_LIVE_AVATAR_BUCKET},
 * {@code MNEMA_S3_LIVE_AVATAR_ACCESS_KEY}, {@code MNEMA_S3_LIVE_AVATAR_SECRET_KEY}. Objects live under a random
 * {@code verify/<uuid>/} prefix and the exact-erase check uses a random account/asset pair; every version is removed
 * afterwards. Run from {@code backend/}: see docs/operations/vps-runtime.md ("Object storage live check").
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_S3_LIVE", matches = "true")
class S3LiveParityTest {
    private static final String PREFIX = "verify/" + UUID.randomUUID() + "/";
    private static final String ERASE_ACCOUNT = UUID.randomUUID().toString();
    private static S3Client admin;
    private static AvatarStorage storage;
    private static String bucket;

    @BeforeAll
    static void connect() {
        URI endpoint = URI.create(env("MNEMA_S3_LIVE_ENDPOINT", "https://storage.yandexcloud.net"));
        String region = env("MNEMA_S3_LIVE_REGION", "ru-central1");
        bucket = require("MNEMA_S3_LIVE_AVATAR_BUCKET");
        String access = require("MNEMA_S3_LIVE_AVATAR_ACCESS_KEY");
        String secret = require("MNEMA_S3_LIVE_AVATAR_SECRET_KEY");
        storage = new AvatarStorage(endpoint, region, bucket, access, secret, false, false);
        admin = S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .build();
    }

    @AfterAll
    static void cleanUp() {
        if (admin == null) return;
        try {
            for (String prefix : List.of(PREFIX, "account-avatar/" + ERASE_ACCOUNT + "/")) {
                var versions = admin.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket)
                        .prefix(prefix).build());
                versions.versions().forEach(v -> deleteVersion(v.key(), v.versionId()));
                versions.deleteMarkers().forEach(m -> deleteVersion(m.key(), m.versionId()));
            }
        } finally {
            admin.close();
            storage.close();
        }
    }

    @Test
    void versionedAvatarFlowMatchesTheProductionContract() {
        String key = PREFIX + "avatar";
        UUID account = UUID.randomUUID(), asset = UUID.randomUUID();
        byte[] first = new byte[]{1, 2, 3, 4}, second = new byte[]{9, 8, 7, 6, 5};

        String v1 = storage.put(key, account, asset, new AvatarImage(first, "image/png", 1, 1));
        String v2 = storage.put(key, account, asset, new AvatarImage(second, "image/png", 1, 1));

        assertThat(v1).isNotBlank();
        assertThat(v2).isNotBlank().isNotEqualTo(v1);
        var head = admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).versionId(v1).build());
        assertThat(head.contentLength()).isEqualTo(first.length);
        assertThat(AvatarStorage.userMetadata(head.metadata())).containsEntry("account-id", account.toString())
                .containsEntry("asset-id", asset.toString());
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(storage.get(key)).isEqualTo(second);

        var listed = admin.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build());
        assertThat(listed.versions()).extracting(v -> v.versionId()).containsExactlyInAnyOrder(v1, v2);

        deleteVersion(key, v1);
        assertThatThrownBy(() -> admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).versionId(v1)
                .build())).isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(404));
        assertThat(storage.get(key)).isEqualTo(second);
    }

    @Test
    void exactEraseRemovesEveryVersionOfTheOwnedAvatar() {
        UUID account = UUID.fromString(ERASE_ACCOUNT), asset = UUID.randomUUID();
        String key = "account-avatar/" + account + "/" + asset;
        storage.put(key, account, asset, new AvatarImage(new byte[]{1}, "image/png", 1, 1));
        storage.put(key, account, asset, new AvatarImage(new byte[]{2, 2}, "image/png", 1, 1));

        storage.deleteOwned(new OwnedAvatarEraser.Manifest(account, asset, key, null, new byte[32]));

        var remaining = admin.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build());
        assertThat(remaining.versions()).isEmpty();
        assertThat(remaining.deleteMarkers()).isEmpty();
    }

    private static void deleteVersion(String key, String versionId) {
        admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).versionId(versionId).build());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String require(String name) {
        return Objects.requireNonNull(System.getenv(name), "Missing environment variable " + name);
    }
}
