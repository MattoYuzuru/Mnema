package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The server-side staging of #296: bytes the server downloaded enter the media pipeline as an untrusted upload under an asset id the caller chose
 * (origin {@code generated}), through the same finalize, seal and processing as a browser upload; on MinIO, with a fake worker for the derivation.
 */
@SpringBootTest
class GeneratedMediaStagerIntegrationTest extends PostgresIntegrationTest {
    private static final String BUCKET = "mnema-staging-test";
    private static final String ACCESS = "mnema-test-access";
    private static final String SECRET = "mnema-test-secret-key";
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
    }

    private static S3Client admin;

    @BeforeAll
    static void createBucket() {
        admin = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + MINIO.getMappedPort(9000))).region(Region.US_EAST_1)
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET))).build();
        admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @Autowired private GeneratedMediaStager stager;
    @Autowired private MediaUploadService uploads;
    @Autowired private MediaObjectStore objects;
    @Autowired private MediaProcessingRepository processingRepository;
    @Autowired private MediaGcRepository mediaGcRepository;
    @Autowired private MediaProcessingSettings processingSettings;
    @Autowired private MediaCatalog catalog;
    @Autowired private JdbcClient jdbc;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4};

    @Test
    void stagedBytesBecomeASealedFrozenSourceOfAnAssetWithTheChosenIdAndOriginGenerated() {
        UUID owner = UUID.randomUUID();
        UUID asset = UUID.randomUUID();

        stager.stage(owner, asset, MediaCatalog.Kind.IMAGE, "image/png", PNG);

        assertThat(jdbc.sql("SELECT origin FROM app_learning.media_asset WHERE asset_id=:asset AND owner_id=:owner").param("asset", asset)
                .param("owner", owner).query(String.class).single()).isEqualTo("generated");
        assertThat(stager.assetState(owner, asset)).isEqualTo(GeneratedMediaStager.State.VERIFYING);
        var sealed = uploads.sealedSource(asset, 0);
        assertThat(sealed.ownerId()).isEqualTo(owner);
        assertThat(sealed.kind()).isEqualTo("image");
        assertThat(sealed.declaredMime()).isEqualTo("image/png");
        assertThat(sealed.declaredLength()).isEqualTo(PNG.length);
        assertThat(read(sealed.objectKey())).isEqualTo(PNG);
        assertThat(jdbc.sql("SELECT state FROM app_learning.media_upload_session WHERE asset_id=:asset").param("asset", asset)
                .query(String.class).single()).isEqualTo("SEALED");
        // another owner sees no such asset
        assertThat(stager.assetState(UUID.randomUUID(), asset)).isEqualTo(GeneratedMediaStager.State.MISSING);
        assertThat(stager.assetState(owner, UUID.randomUUID())).isEqualTo(GeneratedMediaStager.State.MISSING);
    }

    @Test
    void theStagedAssetIsProcessedLikeAnUploadAndEndsReady() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        stager.stage(owner, asset, MediaCatalog.Kind.IMAGE, "image/png", PNG);

        var claim = processingRepository.claim(asset);
        assertThat(claim).isNotNull();
        process(claim, true);

        assertThat(stager.assetState(owner, asset)).isEqualTo(GeneratedMediaStager.State.READY);
        assertThat(catalog.resolve(owner, asset, null).mimeType()).isEqualTo("image/png");
    }

    @Test
    void aRejectedVerificationEndsRejected() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        stager.stage(owner, asset, MediaCatalog.Kind.IMAGE, "image/png", PNG);

        process(processingRepository.claim(asset), false);

        assertThat(stager.assetState(owner, asset)).isEqualTo(GeneratedMediaStager.State.REJECTED);
    }

    @Test
    void stagingTheSameBytesAgainIsANoOpAndOtherBytesUnderTheSameIdConflict() {
        UUID owner = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        stager.stage(owner, asset, MediaCatalog.Kind.IMAGE, "image/png", PNG);

        stager.stage(owner, asset, MediaCatalog.Kind.IMAGE, "image/png", PNG);

        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_upload_session WHERE asset_id=:asset").param("asset", asset)
                .query(Integer.class).single()).isEqualTo(1);
        byte[] other = PNG.clone();
        other[8] = 9;
        assertThatThrownBy(() -> stager.stage(owner, asset, MediaCatalog.Kind.IMAGE, "image/png", other)).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void anAssetOfAnotherOwnerOrAnUploadedAssetCannotBeTakenOverAndATypeThatIsNotAnAllowedImageIsRefused() {
        UUID owner = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        stager.stage(owner, asset, MediaCatalog.Kind.IMAGE, "image/png", PNG);
        assertThatThrownBy(() -> stager.stage(UUID.randomUUID(), asset, MediaCatalog.Kind.IMAGE, "image/png", PNG)).isInstanceOf(ResourceNotFoundException.class);

        UUID uploaded = catalog.reserve(owner, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        assertThatThrownBy(() -> stager.stage(owner, uploaded, MediaCatalog.Kind.IMAGE, "image/png", PNG)).isInstanceOf(MediaUploadConflictException.class);

        assertThatThrownBy(() -> stager.stage(owner, UUID.randomUUID(), MediaCatalog.Kind.IMAGE, "image/svg+xml", PNG)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> stager.stage(owner, UUID.randomUUID(), MediaCatalog.Kind.AUDIO, "image/png", PNG)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> stager.stage(owner, UUID.randomUUID(), MediaCatalog.Kind.IMAGE, "image/png", new byte[0])).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void generatedIsNotAnOriginABrowserMayAskFor() {
        assertThatThrownBy(() -> uploads.start(UUID.randomUUID(), UUID.randomUUID(), "generated", "image", "image/png", 3))
                .isInstanceOf(InvalidRequestException.class);
    }

    private void process(MediaProcessingRepository.Claim claim, boolean valid) throws Exception {
        MediaWorkerGateway fake = (job, asset, generation, kind, length, sha, duration) -> {
            // the Docker worker exits 2 with a safe code for bytes it cannot decode: the gateway raises exactly this
            if (!valid) throw new MediaProcessingRejectedException("image_undecodable");
            try {
                Path output = Files.createDirectory(job.resolve("output"));
                byte[] playback = {11, 12, 13};
                byte[] thumbnail = {21, 22, 23};
                Files.write(output.resolve("image_webp_2048_v1.webp"), playback);
                Files.write(output.resolve("image_webp_320_v1.webp"), thumbnail);
                String sourceInfo = "{\"sha256\":\"" + sha + "\",\"byteLength\":" + length + ",\"mimeType\":\"image/png\",\"durationMs\":null,\"width\":8,\"height\":8}";
                Files.writeString(output.resolve("result.json"), "{\"formatVersion\":1,\"assetId\":\"" + asset + "\",\"generation\":" + generation
                        + ",\"kind\":\"image\",\"source\":" + sourceInfo + ",\"variants\":[" + variant("playback", "image_webp_2048_v1", playback) + ","
                        + variant("thumbnail", "image_webp_320_v1", thumbnail) + "]}");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        new MediaProcessingService(processingRepository, uploads, objects, fake, mediaGcRepository, processingSettings).process(claim);
    }

    private static String variant(String purpose, String profile, byte[] bytes) throws Exception {
        return "{\"purpose\":\"" + purpose + "\",\"profile\":\"" + profile + "\",\"path\":\"" + profile + ".webp\",\"sha256\":\"" + sha(bytes)
                + "\",\"byteLength\":" + bytes.length + ",\"mimeType\":\"image/webp\",\"durationMs\":null,\"width\":8,\"height\":8}";
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] read(String key) {
        return admin.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build(), ResponseTransformer.toBytes()).asByteArray();
    }
}
