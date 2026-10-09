package app.mnema.learning.media;

import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    @Autowired private MediaCatalog catalog;
    @Autowired private MediaObjectStore objects;
    @Autowired private MediaPlaybackStore playbackStore;
    @Autowired private MediaProcessingRepository processingRepository;
    @Autowired private MediaGcRepository mediaGcRepository;
    @Autowired private MediaProcessingSettings processingSettings;
    @Autowired private JdbcClient jdbc;

    /**
     * Local-only proof that the real media runner starts a real media-worker container per job (no network, one
     * throw-away container each) and that the variants it hands back are published through MinIO. The test plays the
     * root runner under the current user, so its verdicts are accepted as owned by that user.
     * {@code MNEMA_MEDIA_WORKER_IMAGE} names the image (default {@code mnema-media-worker:local}).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "MNEMA_MEDIA_SPOOL_SMOKE", matches = "1")
    void realImageAudioAndVideoPassUploadWorkerAndSignedPlayback(@TempDir Path temporary) throws Exception {
        Path image = temporary.resolve("diagram.png");
        Path audio = temporary.resolve("narration.mp3");
        Path video = temporary.resolve("clip.mp4");
        generate("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                "testsrc2=s=96x72:d=0.2", "-frames:v", "1", image.toString());
        generate("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=2", "-c:a", "libmp3lame", audio.toString());
        generate("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                "testsrc2=s=160x90:r=12:d=2", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", video.toString());

        Path state = Files.createDirectories(Path.of(System.getProperty("user.home"), ".mnema"));
        Path workDir = Files.createTempDirectory(state, "spool-smoke-");
        int uid = (Integer) Files.getAttribute(workDir, "unix:uid");
        Path sharedRoot = Files.createDirectory(workDir.resolve("spool"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        var settings = new MediaProcessingSettings(true, sharedRoot.toString(), Duration.ofMinutes(30),
                Duration.ofHours(2), uid, Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofMinutes(1),
                Duration.ofMinutes(30), 5, 2, Duration.ofHours(1), Duration.ofMinutes(5));
        int gid = (Integer) Files.getAttribute(workDir, "unix:gid");
        var processing = new MediaProcessingService(processingRepository, uploads, objects,
                new SpoolMediaWorkerGateway(Duration.ofMinutes(5), Duration.ofMillis(250),
                        SpoolMediaWorkerGateway.DISK_CAP_BYTES, uid), mediaGcRepository, settings);
        String script = "import importlib.util, pathlib, signal, sys\n"
                + "spec = importlib.util.spec_from_file_location('runner', sys.argv[1])\n"
                + "module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)\n"
                + "work = pathlib.Path(sys.argv[2]); uid, gid = int(sys.argv[3]), int(sys.argv[4])\n"
                + "config = module.Config(work_dir=work, state_dir=work / 'state', trusted_uid=uid, learning_uid=uid,\n"
                + "    learning_gid=gid, worker_uid=uid, worker_gid=gid, require_mount=False, image=sys.argv[5], docker='docker')\n"
                + "(work / 'state').mkdir(mode=0o700, exist_ok=True)\n"
                + "runner = module.Runner(config)\n"
                + "signal.signal(signal.SIGTERM, lambda *_: runner.stop.set())\n"
                + "runner.run_forever()\n";
        Path runnerScript = Path.of("..", "..", "..", "deploy", "production", "mnema-media-runner.py").toAbsolutePath().normalize();
        if (!Files.isRegularFile(runnerScript)) runnerScript = Path.of("deploy", "production", "mnema-media-runner.py").toAbsolutePath();
        Process runner = new ProcessBuilder("python3", "-c", script, runnerScript.toString(), workDir.toString(),
                Integer.toString(uid), Integer.toString(gid),
                System.getenv().getOrDefault("MNEMA_MEDIA_WORKER_IMAGE", "mnema-media-worker:local"))
                .inheritIO().start();
        try {
            for (var source : List.of(new LocalMedia(image, "image", "image/png"),
                    new LocalMedia(audio, "audio", "audio/mpeg"),
                    new LocalMedia(video, "video", "video/mp4"))) {
                byte[] bytes = Files.readAllBytes(source.path());
                UUID owner = UUID.randomUUID();
                var started = uploads.start(owner, UUID.randomUUID(), "upload", source.kind(), source.mime(), bytes.length);
                put(started.url(), started.headers(), bytes);
                uploads.finalizeUpload(owner, started.assetId(), 0, UUID.randomUUID());
                var claim = processingRepository.claim(started.assetId());
                assertThat(claim).isNotNull();
                processing.process(claim);
                assertThat(assetState(started.assetId())).as(source.kind()).isEqualTo("READY");
                List<String> keys = jdbc.sql("SELECT b.object_key FROM app_learning.media_variant v "
                                + "JOIN app_learning.media_blob b ON b.blob_id=v.blob_id "
                                + "WHERE v.asset_id=:asset ORDER BY v.profile")
                        .param("asset", started.assetId()).query(String.class).list();
                assertThat(keys).as(source.kind()).isNotEmpty();
                for (String key : keys) {
                    var head = objects.head(key);
                    assertThat(head).as(key).isNotNull();
                    var response = HTTP.send(HttpRequest.newBuilder(URI.create(playbackStore.read(key, false).url()))
                            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    assertThat(response.statusCode()).as(key).isEqualTo(200);
                    assertThat(response.body().length).as(key).isEqualTo(head.size());
                }
            }
        } finally {
            runner.destroy();
            runner.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            try (var paths = Files.walk(workDir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private record LocalMedia(Path path, String kind, String mime) { }

    private static void generate(String... command) throws Exception {
        var process = new ProcessBuilder(command).inheritIO().start();
        assertThat(process.waitFor()).isZero();
    }

    @Test
    void gcNeedsTwoScansAndGraceThenFencesHashDedupAcrossPhysicalDelete(@TempDir Path temporary)
            throws Exception {
        UUID owner = UUID.randomUUID();
        UUID oldAsset = catalog.reserve(owner, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        UUID blob = UUID.randomUUID();
        byte[] bytes = new byte[]{31, 32, 33};
        String key = "derived/" + UUID.randomUUID() + "/0/" + UUID.randomUUID() + "/image_webp_2048_v1/"
                + sha(bytes);
        Path file = Files.write(temporary.resolve("variant.webp"), bytes);
        objects.putVerified(key, file, bytes.length, sha(bytes), "image/webp");
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) "
                        + "VALUES (:blob,:hash,:length,'image/webp',:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", HexFormat.of().parseHex(sha(bytes)))
                .param("length", bytes.length).param("key", key).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state='VERIFYING',updated_at=GREATEST(updated_at,CURRENT_TIMESTAMP) "
                        + "WHERE asset_id=:asset").param("asset", oldAsset).update();
        assertThat(catalog.ready(oldAsset, 0, blob)).isTrue();
        jdbc.sql("UPDATE app_learning.media_asset SET owner_hold_until=CURRENT_TIMESTAMP-interval '1 day' "
                        + "WHERE asset_id=:asset").param("asset", oldAsset).update();
        catalog.expireUnattached(100);
        assertThat(assetState(oldAsset)).isEqualTo("DELETED");
        mediaGcRepository.discover(1000);

        mediaGcRepository.scanKey(key, 0);
        assertThat(gcState(key)).isEqualTo("FIRST");
        mediaGcRepository.scanKey(key, 0);
        assertThat(gcState(key)).isEqualTo("FIRST");
        jdbc.sql("UPDATE app_learning.media_gc_object SET first_scan_at=CURRENT_TIMESTAMP-interval '2 days' "
                        + "WHERE object_key=:key").param("key", key).update();
        mediaGcRepository.scanKey(key, 1);
        assertThat(gcState(key)).isEqualTo("SECOND");

        var replacement = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", bytes.length);
        put(replacement.url(), replacement.headers(), bytes);
        uploads.finalizeUpload(owner, replacement.assetId(), 0, UUID.randomUUID());
        var processing = processingRepository.claim(replacement.assetId());
        var reclaimed = mediaGcRepository.claimDeletion(key);
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.key()).isEqualTo(key);
        assertThatThrownBy(() -> processingRepository.complete(processing,
                new MediaProcessingRepository.Blob(sha(bytes), bytes.length, "image/png",
                        frozenKey(replacement.assetId(), 0)), List.of()))
                .isInstanceOf(MediaStorageUnavailableException.class);

        objects.delete(key);
        assertThat(mediaGcRepository.completeDeletion(reclaimed)).isTrue();
        assertThat(mediaGcRepository.completeDeletion(reclaimed)).isFalse();
        assertThat(gcState(key)).isEqualTo("DELETED");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_blob WHERE blob_id=:blob")
                .param("blob", blob).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT source_blob_id FROM app_learning.media_asset WHERE asset_id=:asset")
                .param("asset", oldAsset).query(UUID.class).optional()).isEmpty();
        assertThat(objects.head(key)).isNull();
        assertThat(processingRepository.complete(processing,
                new MediaProcessingRepository.Blob(sha(bytes), bytes.length, "image/png",
                        frozenKey(replacement.assetId(), 0)), List.of())).isTrue();
        assertThat(assetState(replacement.assetId())).isEqualTo("READY");
        assertThat(read(frozenKey(replacement.assetId(), 0))).containsExactly(bytes);
    }

    @Test
    void gcPreservesActiveWorkerAndRetrySourceButReclaimsOrphanDerivedAndRejectedSource(@TempDir Path temporary)
            throws Exception {
        UUID owner = UUID.randomUUID();
        byte[] source = new byte[]{41, 42, 43};
        var started = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", source.length);
        put(started.url(), started.headers(), source);
        uploads.finalizeUpload(owner, started.assetId(), 0, UUID.randomUUID());
        var claim = processingRepository.claim(started.assetId());
        byte[] variant = new byte[]{51, 52, 53};
        String orphan = "derived/" + claim.assetId() + "/0/" + claim.token()
                + "/image_webp_320_v1/" + sha(variant);
        mediaGcRepository.recordDerivedIntent(claim, orphan);
        Path file = Files.write(temporary.resolve("orphan.webp"), variant);
        objects.putVerified(orphan, file, variant.length, sha(variant), "image/webp");
        mediaGcRepository.scanKey(orphan, 0);
        assertThat(gcState(orphan)).isEqualTo("TRACKED");
        assertThat(processingRepository.retryable(claim, "worker_unavailable")).isTrue();
        String sealed = frozenKey(started.assetId(), 0);
        mediaGcRepository.discover(1000);
        mediaGcRepository.scanKey(sealed, 0);
        assertThat(gcState(sealed)).isEqualTo("TRACKED"); // preserved owner retry

        mediaGcRepository.scanKey(orphan, 0);
        assertThat(gcState(orphan)).isEqualTo("FIRST");
        jdbc.sql("UPDATE app_learning.media_gc_object SET first_scan_at=CURRENT_TIMESTAMP-interval '2 days' "
                        + "WHERE object_key=:key").param("key", orphan).update();
        mediaGcRepository.scanKey(orphan, 1);
        assertThat(gcState(orphan)).isEqualTo("SECOND");
        var derivedDelete = mediaGcRepository.claimDeletion(orphan);
        assertThat(derivedDelete.key()).isEqualTo(orphan);
        objects.delete(orphan);
        assertThat(mediaGcRepository.completeDeletion(derivedDelete)).isTrue();
        assertThat(objects.head(orphan)).isNull();
        assertThat(read(sealed)).containsExactly(source);

        jdbc.sql("UPDATE app_learning.media_asset SET state='REJECTED',updated_at=GREATEST(updated_at,CURRENT_TIMESTAMP) "
                        + "WHERE asset_id=:asset").param("asset", started.assetId()).update();
        mediaGcRepository.scanKey(sealed, 0);
        assertThat(gcState(sealed)).isEqualTo("FIRST");
        jdbc.sql("UPDATE app_learning.media_gc_object SET first_scan_at=CURRENT_TIMESTAMP-interval '2 days' "
                        + "WHERE object_key=:key").param("key", sealed).update();
        mediaGcRepository.scanKey(sealed, 1);
        var sourceDelete = mediaGcRepository.claimDeletion(sealed);
        assertThat(sourceDelete.key()).isEqualTo(sealed);
        objects.delete(sealed);
        assertThat(mediaGcRepository.completeDeletion(sourceDelete)).isTrue();
        assertThat(objects.head(sealed)).isNull();
    }

    private String gcState(String key) {
        return jdbc.sql("SELECT state FROM app_learning.media_gc_object WHERE object_key=:key")
                .param("key", key).query(String.class).single();
    }

    @Test
    void signedPlaybackGetSupportsBrowserRangeSeekingAndAttachmentDownload() throws Exception {
        String key = "verified/range-" + UUID.randomUUID();
        admin.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).contentType("video/mp4").build(),
                RequestBody.fromBytes(new byte[] {0, 1, 2, 3, 4, 5}));
        var signed = playbackStore.read(key, false);
        HttpResponse<byte[]> range = HTTP.send(HttpRequest.newBuilder(URI.create(signed.url()))
                .header("Range", "bytes=2-4").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.body()).containsExactly(2, 3, 4);
        assertThat(range.headers().firstValue("Content-Range")).hasValue("bytes 2-4/6");
        var download = playbackStore.read(key, true);
        HttpResponse<Void> attachment = HTTP.send(HttpRequest.newBuilder(URI.create(download.url())).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(attachment.statusCode()).isEqualTo(200);
        assertThat(attachment.headers().firstValue("Content-Disposition")).hasValue("attachment");
    }

    @Test
    void sealedSourcePublishesOnlyAfterVerifiedDerivedObjectsAndCurrentLease() throws Exception {
        UUID owner = UUID.randomUUID();
        byte[] source = new byte[]{1, 2, 3};
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", source.length);
        put(start.url(), start.headers(), source);
        uploads.finalizeUpload(owner, start.assetId(), 0, UUID.randomUUID());
        var claim = processingRepository.claim(start.assetId());
        assertThat(claim).isNotNull();
        assertThat(claim.assetId()).isEqualTo(start.assetId());
        MediaWorkerGateway fake = (job, asset, generation, kind, length, sha, duration) -> {
            try {
                assertThat(Files.readAllBytes(job.resolve("source"))).containsExactly(source);
                Path output = Files.createDirectory(job.resolve("output"));
                byte[] playback = new byte[]{11, 12, 13};
                byte[] thumbnail = new byte[]{21, 22, 23};
                Files.write(output.resolve("image_webp_2048_v1.webp"), playback);
                Files.write(output.resolve("image_webp_320_v1.webp"), thumbnail);
                String sourceInfo = "{\"sha256\":\"" + sha + "\",\"byteLength\":3,"
                        + "\"mimeType\":\"image/png\",\"durationMs\":null,\"width\":8,\"height\":8}";
                String result = "{\"formatVersion\":1,\"assetId\":\"" + asset
                        + "\",\"generation\":" + generation + ",\"kind\":\"image\",\"source\":" + sourceInfo
                        + ",\"variants\":[" + fakeVariant("playback", "image_webp_2048_v1", playback)
                        + "," + fakeVariant("thumbnail", "image_webp_320_v1", thumbnail) + "]}";
                Files.writeString(output.resolve("result.json"), result);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        };
        new MediaProcessingService(processingRepository, uploads, objects, fake, mediaGcRepository,
                processingSettings).process(claim);

        assertThat(assetState(start.assetId())).isEqualTo("READY");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_variant WHERE asset_id=:asset")
                .param("asset", start.assetId()).query(Integer.class).single()).isEqualTo(2);
        String key = "derived/" + claim.assetId() + "/" + claim.generation() + "/" + claim.token()
                + "/image_webp_2048_v1/" + sha(new byte[]{11, 12, 13});
        assertThat(read(key)).containsExactly(11, 12, 13);
        assertThat(processingRepository.heartbeat(claim)).isFalse();
    }

    @Test
    void expiredProcessingTokenCannotPublishAndPreservedBytesCanBeRequeued() throws Exception {
        UUID owner = UUID.randomUUID();
        var start = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        put(start.url(), start.headers(), new byte[]{7, 8, 9});
        uploads.finalizeUpload(owner, start.assetId(), 0, UUID.randomUUID());
        var stale = processingRepository.claim(start.assetId());
        jdbc.sql("UPDATE app_learning.media_upload_session SET lease_until=CURRENT_TIMESTAMP-interval '1 second' "
                        + "WHERE session_id=:session")
                .param("session", stale.sessionId()).update();
        assertThat(processingRepository.complete(stale,
                new MediaProcessingRepository.Blob(sha(new byte[]{7, 8, 9}), 3, "image/png", frozenKey(start.assetId(), 0)),
                List.of())).isFalse();
        var current = processingRepository.claim(start.assetId());
        assertThat(current.token()).isNotEqualTo(stale.token());
        assertThat(processingRepository.rejected(stale, "stale_worker")).isFalse();
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_attempts=:maximum,"
                        + "lease_until=CURRENT_TIMESTAMP-interval '1 second' WHERE session_id=:session")
                .param("maximum", processingSettings.maxAttempts).param("session", current.sessionId()).update();
        assertThat(processingRepository.claim(start.assetId())).isNull();
        assertThat(assetState(start.assetId())).isEqualTo("FAILED_RETRYABLE");
        assertThat(read(frozenKey(start.assetId(), 0))).containsExactly(7, 8, 9);

        var processing = new MediaProcessingService(processingRepository, uploads, objects,
                (job, asset, generation, kind, length, digest, maxDuration) -> { }, mediaGcRepository,
                processingSettings);
        processing.retryPreserved(owner, start.assetId(), 0);
        assertThat(assetState(start.assetId())).isEqualTo("VERIFYING");
        var retried = processingRepository.claim(start.assetId());
        assertThat(retried.attempt()).isEqualTo(1);
        assertThat(retried.token()).isNotEqualTo(current.token());
    }

    @Test
    void invalidMediaIsRejectedAndTransientWorkerFailureIsScheduledForRetry() throws Exception {
        UUID owner = UUID.randomUUID();
        var invalid = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        put(invalid.url(), invalid.headers(), new byte[]{1, 1, 1});
        uploads.finalizeUpload(owner, invalid.assetId(), 0, UUID.randomUUID());
        var rejecting = new MediaProcessingService(processingRepository, uploads, objects,
                (job, asset, generation, kind, length, digest, maxDuration) -> {
                    throw new MediaProcessingRejectedException("unsupported_image");
                }, mediaGcRepository, processingSettings);
        rejecting.process(processingRepository.claim(invalid.assetId()));
        assertThat(assetState(invalid.assetId())).isEqualTo("REJECTED");
        assertThat(processingRepository.claim(invalid.assetId())).isNull();

        var transientSource = uploads.start(owner, UUID.randomUUID(), "upload", "image", "image/png", 3);
        put(transientSource.url(), transientSource.headers(), new byte[]{2, 2, 2});
        uploads.finalizeUpload(owner, transientSource.assetId(), 0, UUID.randomUUID());
        var retrying = new MediaProcessingService(processingRepository, uploads, objects,
                (job, asset, generation, kind, length, digest, maxDuration) -> {
                    throw new MediaStorageUnavailableException();
                }, mediaGcRepository, processingSettings);
        retrying.process(processingRepository.claim(transientSource.assetId()));
        assertThat(assetState(transientSource.assetId())).isEqualTo("PROCESSING");
        assertThat(processingRepository.claim(transientSource.assetId())).isNull();
        assertThat(jdbc.sql("SELECT processing_next_attempt_at>CURRENT_TIMESTAMP "
                        + "FROM app_learning.media_upload_session WHERE asset_id=:asset")
                .param("asset", transientSource.assetId()).query(Boolean.class).single()).isTrue();
    }

    private static String fakeVariant(String purpose, String profile, byte[] bytes) {
        return "{\"purpose\":\"" + purpose + "\",\"profile\":\"" + profile
                + "\",\"path\":\"" + profile + ".webp\",\"sha256\":\"" + sha(bytes)
                + "\",\"byteLength\":" + bytes.length + ",\"mimeType\":\"image/webp\","
                + "\"durationMs\":null,\"width\":8,\"height\":8}";
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

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
        jdbc.sql("UPDATE app_learning.media_asset SET state='PROCESSING',updated_at=GREATEST(updated_at,CURRENT_TIMESTAMP) "
                        + "WHERE asset_id=:asset")
                .param("asset", start.assetId()).update();
        assertThat(uploads.status(owner, start.assetId()).assetState()).isEqualTo("PROCESSING");
        jdbc.sql("UPDATE app_learning.media_asset SET state='REJECTED',updated_at=GREATEST(updated_at,CURRENT_TIMESTAMP) "
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
