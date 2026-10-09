package app.mnema.learning.media;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in parity check of the Learning object-store operations against the real Yandex Object Storage media bucket
 * (default SSE-KMS). Skipped unless {@code MNEMA_S3_LIVE=true}. Configuration comes only from the environment and is
 * never printed: {@code MNEMA_S3_LIVE_ENDPOINT} (default https://storage.yandexcloud.net),
 * {@code MNEMA_S3_LIVE_REGION} (default ru-central1), {@code MNEMA_S3_LIVE_MEDIA_BUCKET},
 * {@code MNEMA_S3_LIVE_MEDIA_ACCESS_KEY}, {@code MNEMA_S3_LIVE_MEDIA_SECRET_KEY}. Objects live under a random
 * {@code verify/<uuid>/} prefix and are removed afterwards. Run from {@code backend/}: see
 * docs/operations/vps-runtime.md ("Object storage live check").
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_S3_LIVE", matches = "true")
class S3LiveParityTest {
    private static final String PREFIX = "verify/" + UUID.randomUUID() + "/";
    private static final long PART = 5_242_880L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Set<String> RESTRICTED = Set.of("content-length", "host", "connection", "expect", "upgrade");
    private static final List<String> KEYS = new ArrayList<>();
    private static final List<String[]> MULTIPARTS = new ArrayList<>();
    private static S3Client admin;
    private static MediaObjectStore store;
    private static MediaPlaybackStore playback;
    private static String bucket;

    @BeforeAll
    static void connect() {
        URI endpoint = URI.create(env("MNEMA_S3_LIVE_ENDPOINT", "https://storage.yandexcloud.net"));
        String region = env("MNEMA_S3_LIVE_REGION", "ru-central1");
        bucket = require("MNEMA_S3_LIVE_MEDIA_BUCKET");
        String access = require("MNEMA_S3_LIVE_MEDIA_ACCESS_KEY");
        String secret = require("MNEMA_S3_LIVE_MEDIA_SECRET_KEY");
        var settings = new MediaUploadSettings(endpoint, region, bucket, access, secret, false, 67_108_864L,
                536_870_912L, 4_294_967_296L, 8_589_934_592L, 3, PART, PART, Duration.ofMinutes(15),
                Duration.ofHours(24), Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(15));
        store = new MediaObjectStore(settings);
        playback = new MediaPlaybackStore(settings, new MediaPlaybackSettings(Duration.ofHours(1)));
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
            for (String[] upload : MULTIPARTS) {
                try {
                    admin.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(upload[0])
                            .uploadId(upload[1]).build());
                } catch (S3Exception ignored) { /* Already completed or aborted. */ }
            }
            for (String key : KEYS) {
                admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            }
            assertThat(admin.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket)
                    .prefix(PREFIX).build()).uploads()).isEmpty();
        } finally {
            playback.close();
            store.close();
            admin.close();
        }
    }

    @Test
    void presignedSinglePutWithContentLengthIsStoredEncrypted() throws Exception {
        String key = track("single");
        byte[] bytes = bytes(2048, 1);

        var signed = store.singleUrl(key, bytes.length);
        assertThat(put(signed, bytes).statusCode()).isEqualTo(200);

        assertThat(store.head(key).size()).isEqualTo(bytes.length);
        assertEncrypted(key);
    }

    @Test
    void presignedMultipartCreatePartListCompleteIsStoredEncrypted() throws Exception {
        String key = track("multipart");
        String upload = store.createMultipart(key);
        MULTIPARTS.add(new String[]{key, upload});
        byte[] first = bytes((int) PART, 2), last = bytes(1024, 3);

        var one = put(store.partUrl(key, upload, 1, first.length), first);
        var two = put(store.partUrl(key, upload, 2, last.length), last);
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(two.statusCode()).isEqualTo(200);

        var parts = store.parts(key, upload);
        assertThat(parts).extracting(MediaObjectStore.StoredPart::number).containsExactly(1, 2);
        assertThat(parts).extracting(MediaObjectStore.StoredPart::size).containsExactly((long) first.length, (long) last.length);
        store.complete(key, upload, parts);

        assertThat(store.head(key).size()).isEqualTo(first.length + last.length);
        assertEncrypted(key);
    }

    @Test
    void conditionalCopyPassesOnMatchAndFailsWith412OnMismatch() throws Exception {
        String source = track("copy-source"), target = track("copy-target");
        byte[] bytes = bytes(512, 4);
        assertThat(put(store.singleUrl(source, bytes.length), bytes).statusCode()).isEqualTo(200);
        var stored = store.head(source);

        assertThatThrownBy(() -> store.freeze(source, target, "\"00000000000000000000000000000000\""))
                .isInstanceOf(MediaUploadConflictException.class);
        assertThat(store.head(target)).isNull();

        store.freeze(source, target, stored.eTag());
        assertThat(store.head(target).size()).isEqualTo(bytes.length);
        assertEncrypted(target);
    }

    @Test
    void ifNoneMatchPutRejectsASecondWriterAndReconcilesAnIdenticalRetry(@TempDir Path dir) throws IOException {
        String key = track("derived");
        byte[] bytes = bytes(300, 5);
        Path file = Files.write(dir.resolve("derived.bin"), bytes);
        String sha = java.util.HexFormat.of().formatHex(sha256(bytes));

        store.putVerified(key, file, bytes.length, sha, "application/octet-stream");

        assertThatThrownBy(() -> admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*")
                .contentLength((long) bytes.length).build(), RequestBody.fromBytes(bytes)))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(412));
        // A retried identical PUT is reconciled by HEAD instead of failing.
        store.putVerified(key, file, bytes.length, sha, "application/octet-stream");
        assertEncrypted(key);
    }

    @Test
    void presignedGetHonoursRangeAndDeleteRemovesTheObject() throws Exception {
        String key = track("playback");
        byte[] bytes = bytes(4096, 6);
        assertThat(put(store.singleUrl(key, bytes.length), bytes).statusCode()).isEqualTo(200);

        var response = HTTP.send(HttpRequest.newBuilder(URI.create(playback.read(key, false).url()))
                .header("Range", "bytes=10-19").GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.body()).isEqualTo(java.util.Arrays.copyOfRange(bytes, 10, 20));

        store.delete(key);
        assertThat(store.head(key)).isNull();
    }

    private static HttpResponse<byte[]> put(MediaObjectStore.SignedUrl signed, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(signed.url()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        signed.headers().forEach((name, value) -> {
            if (!RESTRICTED.contains(name.toLowerCase(java.util.Locale.ROOT))) request.header(name, value);
        });
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertEncrypted(String key) {
        assertThat(admin.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                .serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
    }

    private static String track(String name) {
        String key = PREFIX + name;
        KEYS.add(key);
        return key;
    }

    private static byte[] bytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String require(String name) {
        return Objects.requireNonNull(System.getenv(name), "Missing environment variable " + name);
    }
}
