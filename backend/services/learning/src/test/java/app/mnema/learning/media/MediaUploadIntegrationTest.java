package app.mnema.learning.media;

import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MediaUploadIntegrationTest extends PostgresIntegrationTest {
    private static final String BUCKET = "mnema-upload-test";
    private static final String ACCESS = "mnema-test-access";
    private static final String SECRET = "mnema-test-secret-key";
    // Same pinned CI-only fixture as the purge rehearsal; it is not a runtime dependency.
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "ghcr.io/l33tlamer/minio-backup@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e"))
            .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static { MINIO.start(); }

    @DynamicPropertySource
    static void configureMedia(DynamicPropertyRegistry registry) {
        registry.add("learning.media.upload.endpoint", () -> "http://127.0.0.1:" + MINIO.getMappedPort(9000));
        registry.add("learning.media.upload.allow-loopback-http", () -> "true");
        registry.add("learning.media.upload.region", () -> "us-east-1");
        registry.add("learning.media.upload.bucket", () -> BUCKET);
        registry.add("learning.media.upload.access-key", () -> ACCESS);
        registry.add("learning.media.upload.secret-key", () -> SECRET);
        registry.add("learning.media.upload.multipart-threshold", () -> "6291456");
        registry.add("learning.media.upload.part-size", () -> "5242880");
    }

    private static S3Client admin;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void createBucket() {
        admin = S3Client.builder().endpointOverride(endpoint()).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
                .build();
        admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @Autowired private MediaUploadService uploads;
    @Autowired private MediaObjectStore objects;
    @Autowired private JdbcClient jdbc;

    @Test
    void singlePutIsFrozenBeforeVerificationAndOldPutCannotMutateIt() throws Exception {
        UUID owner = UUID.randomUUID();
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        assertThat(start.method()).isEqualTo("SINGLE");
        put(start.url(), start.headers(), new byte[] {1, 2, 3});
        UUID command = UUID.randomUUID();
        assertThat(uploads.finalizeUpload(owner, start.assetId(), start.generation(), command).state())
                .isEqualTo("SEALED");
        String frozen = frozenKey(start.assetId(), 0);
        assertThat(uploads.sealedSource(start.assetId(), 0).objectKey()).isEqualTo(frozen);
        assertThatThrownBy(() -> uploads.sealedSource(start.assetId(), 1))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(read(frozen)).containsExactly(1, 2, 3);
        put(start.url(), start.headers(), new byte[] {4, 5, 6});
        assertThat(read(frozen)).containsExactly(1, 2, 3);
        assertThat(uploads.finalizeUpload(owner, start.assetId(), 0, command).state()).isEqualTo("SEALED");
        assertThatThrownBy(() -> uploads.finalizeUpload(owner, start.assetId(), 0, UUID.randomUUID()))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(assetState(start.assetId())).isEqualTo("VERIFYING");
        assertThat(uploads.status(owner, start.assetId()).assetState()).isEqualTo("VERIFYING");
        jdbc.sql("UPDATE app_learning.media_asset SET state='PROCESSING',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE asset_id=:asset")
                .param("asset", start.assetId()).update();
        assertThat(uploads.status(owner, start.assetId()).assetState()).isEqualTo("PROCESSING");
        jdbc.sql("UPDATE app_learning.media_asset SET state='REJECTED',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE asset_id=:asset")
                .param("asset", start.assetId()).update();
        assertThat(uploads.status(owner, start.assetId()).assetState()).isEqualTo("REJECTED");
    }

    @Test
    void singlePutUrlCanBeRenewedAfterReloadOnlyWhileOwnerSessionIsOpen() throws Exception {
        UUID owner = UUID.randomUUID();
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        assertThat(uploads.status(owner, start.assetId()).url()).isNull();
        var renewed = uploads.singleUrl(owner, start.assetId(), start.generation());
        assertThat(renewed.assetId()).isEqualTo(start.assetId());
        assertThat(renewed.generation()).isEqualTo(start.generation());
        assertThat(renewed.url()).isNotBlank();
        assertThat(renewed.headers()).containsEntry("content-length", "3");
        assertThat(renewed.urlExpiresAt()).isAfter(Instant.now());
        assertThatThrownBy(() -> uploads.singleUrl(UUID.randomUUID(), start.assetId(), 0))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> uploads.singleUrl(owner, start.assetId(), 1))
                .isInstanceOf(MediaUploadConflictException.class);
        put(renewed.url(), renewed.headers(), new byte[] {1, 2, 3});
        uploads.finalizeUpload(owner, start.assetId(), 0, UUID.randomUUID());
        assertThatThrownBy(() -> uploads.singleUrl(owner, start.assetId(), 0))
                .isInstanceOf(MediaUploadConflictException.class);

        var expiring = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        jdbc.sql("UPDATE app_learning.media_upload_session SET created_at=CURRENT_TIMESTAMP-interval '2 days',"
                        + "expires_at=CURRENT_TIMESTAMP-interval '1 second' "
                        + "WHERE asset_id=:asset")
                .param("asset", expiring.assetId()).update();
        assertThatThrownBy(() -> uploads.singleUrl(owner, expiring.assetId(), 0))
                .isInstanceOf(MediaUploadConflictException.class);
    }

    @Test
    void signedLengthRejectsAClientThatSendsMoreThanReserved() throws Exception {
        var start = uploads.start(UUID.randomUUID(), UUID.randomUUID(), "upload", "image", "image/png", 3);
        assertThat(start.headers()).containsKey("content-length");
        assertThat(putStatus(start.url(), start.headers(), new byte[] {1, 2, 3, 4}))
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void browserRecordedOpusIsAcceptedAsSourceInput() {
        var upload = uploads.start(UUID.randomUUID(), UUID.randomUUID(), "recording", "audio",
                "audio/webm;codecs=opus", 128);
        assertThat(upload.method()).isEqualTo("SINGLE");
        assertThat(upload.declaredMime()).isEqualTo("audio/webm;codecs=opus");
    }

    @Test
    void cleanupWaitsForEverySignedUrlAndPreservesTheFrozenSource() throws Exception {
        UUID owner = UUID.randomUUID();
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        put(start.url(), start.headers(), new byte[] {7, 8, 9});
        uploads.finalizeUpload(owner, start.assetId(), 0, UUID.randomUUID());
        String stage = stagingKey(start.assetId(), 0);
        String frozen = frozenKey(start.assetId(), 0);
        uploads.cleanup();
        assertThat(admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(stage).build()).contentLength())
                .isEqualTo(3);
        jdbc.sql("UPDATE app_learning.media_upload_session SET created_at=CURRENT_TIMESTAMP-interval '2 hours',"
                        + "issued_until=CURRENT_TIMESTAMP-interval '1 hour' "
                        + "WHERE asset_id=:asset")
                .param("asset", start.assetId()).update();
        uploads.cleanup();
        assertThatThrownBy(() -> admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(stage).build()))
                .isInstanceOf(S3Exception.class);
        assertThat(read(frozen)).containsExactly(7, 8, 9);
    }

    @Test
    void uncertainSingleCopyDoesNotIssueASecondWriteToFrozenKey() throws Exception {
        UUID owner = UUID.randomUUID();
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        put(start.url(), start.headers(), new byte[] {1, 2, 3});
        UUID command = UUID.randomUUID();
        jdbc.sql("UPDATE app_learning.media_upload_session SET state='FINALIZING',"
                        + "finalize_command_id=:command,copy_started_at=CURRENT_TIMESTAMP-interval '1 hour',"
                        + "lease_until=CURRENT_TIMESTAMP-interval '1 minute' WHERE asset_id=:asset")
                .param("asset", start.assetId()).param("command", command).update();
        assertThatThrownBy(() -> uploads.finalizeUpload(owner, start.assetId(), 0, command))
                .isInstanceOf(MediaUploadConflictException.class);
        assertThat(assetState(start.assetId())).isEqualTo("FAILED_RETRYABLE");
        assertThatThrownBy(() -> admin.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(frozenKey(start.assetId(), 0)).build()))
                .isInstanceOf(S3Exception.class);
        assertThat(uploads.retry(owner, start.assetId(), UUID.randomUUID(), "image", "image/png", 3).generation())
                .isEqualTo(1);
    }

    @Test
    void multipartResumesFromStoragePartsAndServerCompletesExactSizes() throws Exception {
        UUID owner = UUID.randomUUID();
        int part = 5 * 1024 * 1024;
        long length = 2L * part + 17;
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "video", "video/mp4", length);
        assertThat(start.method()).isEqualTo("MULTIPART");
        assertThat(start.partCount()).isEqualTo(3);
        var urls = uploads.partUrls(owner, start.assetId(), 0, 1, 3).parts();
        put(urls.get(0).url(), urls.get(0).headers(), filled(part, (byte) 1));
        put(urls.get(1).url(), urls.get(1).headers(), filled(part, (byte) 2));
        assertThat(uploads.uploadedParts(owner, start.assetId(), 0)).containsExactly(1, 2);
        UUID command = UUID.randomUUID();
        assertThatThrownBy(() -> uploads.finalizeUpload(owner, start.assetId(), 0, command))
                .isInstanceOf(MediaUploadConflictException.class);
        put(urls.get(2).url(), urls.get(2).headers(), filled(17, (byte) 3));
        assertThat(uploads.finalizeUpload(owner, start.assetId(), 0, command).state()).isEqualTo("SEALED");
        String key = stagingKey(start.assetId(), 0);
        assertThat(admin.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build()).contentLength())
                .isEqualTo(length);
        assertThat(assetState(start.assetId())).isEqualTo("VERIFYING");
    }

    @Test
    void completedMultipartIsReconciledWhenTheDatabaseReceiptWasLost() throws Exception {
        UUID owner = UUID.randomUUID();
        int part = 5 * 1024 * 1024;
        long length = 6L * 1024 * 1024 + 11;
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "video", "video/mp4", length);
        var urls = uploads.partUrls(owner, start.assetId(), 0, 1, 2).parts();
        put(urls.get(0).url(), urls.get(0).headers(), filled(part, (byte) 1));
        put(urls.get(1).url(), urls.get(1).headers(), filled(Math.toIntExact(length - part), (byte) 2));
        String key = stagingKey(start.assetId(), 0);
        String storageUpload = jdbc.sql("SELECT storage_upload_id FROM app_learning.media_upload_session "
                        + "WHERE asset_id=:asset")
                .param("asset", start.assetId()).query(String.class).single();
        objects.complete(key, storageUpload, objects.parts(key, storageUpload));
        assertThat(uploads.finalizeUpload(owner, start.assetId(), 0, UUID.randomUUID()).state())
                .isEqualTo("SEALED");
        assertThat(assetState(start.assetId())).isEqualTo("VERIFYING");
    }

    @Test
    void cancelledMultipartPartsAreAbortedAndCleanedAfterUrlExpiry() throws Exception {
        UUID owner = UUID.randomUUID();
        int part = 5 * 1024 * 1024;
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "video", "video/mp4",
                6L * 1024 * 1024 + 11);
        var url = uploads.partUrls(owner, start.assetId(), 0, 1, 1).parts().getFirst();
        put(url.url(), url.headers(), filled(part, (byte) 1));
        assertThat(uploads.cancel(owner, start.assetId(), 0).state()).isEqualTo("ABORTING");
        assertThat(putStatus(url.url(), url.headers(), filled(part, (byte) 1))).isGreaterThanOrEqualTo(400);
        jdbc.sql("UPDATE app_learning.media_upload_session SET created_at=CURRENT_TIMESTAMP-interval '2 hours',"
                        + "issued_until=CURRENT_TIMESTAMP-interval '1 hour' "
                        + "WHERE asset_id=:asset")
                .param("asset", start.assetId()).update();
        uploads.cleanup();
        assertThat(jdbc.sql("SELECT state FROM app_learning.media_upload_session WHERE asset_id=:asset")
                .param("asset", start.assetId()).query(String.class).single()).isEqualTo("ABORTED");
    }

    @Test
    void retryFencesOldGenerationAndOwnerQuotaIsSerialized() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID intent = UUID.randomUUID();
        var first = uploads.start(owner, intent, "upload", "image", "image/png", 3);
        assertThatThrownBy(() -> uploads.status(UUID.randomUUID(), first.assetId()))
                .isInstanceOf(ResourceNotFoundException.class);
        uploads.cancel(owner, first.assetId(), 0);
        UUID retry = UUID.randomUUID();
        var second = uploads.retry(owner, first.assetId(), retry, "image", "image/png", 3);
        assertThat(second.generation()).isEqualTo(1);
        assertThat(uploads.start(owner, intent, "upload", "image", "image/png", 3).generation()).isZero();
        assertThat(uploads.start(owner, intent, "upload", "image", "image/png", 3).url()).isNull();
        assertThat(uploads.retry(owner, first.assetId(), retry, "image", "image/png", 3).generation())
                .isEqualTo(1);
        assertThatThrownBy(() -> uploads.retry(owner, first.assetId(), retry, "image", "image/png", 4))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> uploads.finalizeUpload(owner, first.assetId(), 0, UUID.randomUUID()))
                .isInstanceOf(MediaUploadConflictException.class);
        put(first.url(), first.headers(), new byte[] {1, 2, 3});
        put(second.url(), second.headers(), new byte[] {4, 5, 6});
        uploads.finalizeUpload(owner, first.assetId(), 1, UUID.randomUUID());
        assertThat(read(frozenKey(first.assetId(), 1))).containsExactly(4, 5, 6);

        UUID quotaOwner = UUID.randomUUID();
        for (int i = 0; i < 3; i++) uploads.start(quotaOwner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        assertThatThrownBy(() -> uploads.start(quotaOwner, UUID.randomUUID(), "upload", "image", "image/png", 3))
                .isInstanceOf(ResourceLimitExceededException.class);
    }

    @Test
    void concurrentReservationsCannotExceedOwnerQuota() throws Exception {
        UUID owner = UUID.randomUUID();
        var ready = new CountDownLatch(5);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(5)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    try {
                        uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
                        return true;
                    } catch (ResourceLimitExceededException expected) { return false; }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_upload_session WHERE owner_id=:owner")
                .param("owner", owner).query(Long.class).single()).isEqualTo(3);
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static void put(String url, Map<String, String> headers, byte[] bytes) throws Exception {
        assertThat(putStatus(url, headers, bytes)).as("presigned PUT response").isEqualTo(200);
    }

    private static int putStatus(String url, Map<String, String> headers, byte[] bytes) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length")) builder.header(name, value);
        });
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private static URI endpoint() { return URI.create("http://127.0.0.1:" + MINIO.getMappedPort(9000)); }

    private static byte[] read(String key) {
        return admin.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
                ResponseTransformer.toBytes()).asByteArray();
    }

    private String frozenKey(UUID asset, long generation) {
        return jdbc.sql("SELECT frozen_key FROM app_learning.media_upload_session WHERE asset_id=:asset AND generation=:generation")
                .param("asset", asset).param("generation", generation).query(String.class).single();
    }

    private String stagingKey(UUID asset, long generation) {
        return jdbc.sql("SELECT staging_key FROM app_learning.media_upload_session WHERE asset_id=:asset AND generation=:generation")
                .param("asset", asset).param("generation", generation).query(String.class).single();
    }

    private String assetState(UUID asset) {
        return jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:asset")
                .param("asset", asset).query(String.class).single();
    }
}
