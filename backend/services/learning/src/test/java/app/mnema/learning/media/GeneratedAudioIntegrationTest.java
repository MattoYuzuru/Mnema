package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Synthesised speech in the media pipeline (#297), on MinIO with a fake worker for the derivation: a PCM WAV source is accepted for an asset the server
 * staged (and only for one), the verified blobs of a READY clip are adopted by another owner's new asset without staging or processing, and the speech cache
 * keeps those blobs out of the media GC until its entry is gone.
 */
@SpringBootTest
class GeneratedAudioIntegrationTest extends PostgresIntegrationTest {
    private static final String BUCKET = "mnema-audio-test";
    private static final String ACCESS = "mnema-test-access";
    private static final String SECRET = "mnema-test-secret-key";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
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

    @BeforeAll
    static void createBucket() {
        S3Client admin = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + MINIO.getMappedPort(9000))).region(Region.US_EAST_1)
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

    /** A PCM WAV is what Gemini returns; the bytes only need the type here (the worker is a double). */
    private static final byte[] WAV = "RIFF....WAVEfmt fake pcm bytes".getBytes(StandardCharsets.US_ASCII);

    private void process(MediaProcessingRepository.Claim claim, String sourceMime) throws Exception {
        MediaWorkerGateway fake = (job, asset, generation, kind, length, sha, duration) -> {
            try {
                Path output = Files.createDirectory(job.resolve("output"));
                byte[] playback = {11, 12, 13, (byte) (asset.hashCode() & 0x7f)};
                Files.write(output.resolve("audio_aac_m4a_v1.m4a"), playback);
                String source = "{\"sha256\":\"" + sha + "\",\"byteLength\":" + length + ",\"mimeType\":\"" + sourceMime + "\",\"durationMs\":1500,\"width\":null,\"height\":null}";
                String variant = "{\"purpose\":\"playback\",\"profile\":\"audio_aac_m4a_v1\",\"path\":\"audio_aac_m4a_v1.m4a\",\"sha256\":\"" + sha(playback)
                        + "\",\"byteLength\":" + playback.length + ",\"mimeType\":\"audio/mp4\",\"durationMs\":1500,\"width\":null,\"height\":null}";
                Files.writeString(output.resolve("result.json"), "{\"formatVersion\":1,\"assetId\":\"" + asset + "\",\"generation\":" + generation
                        + ",\"kind\":\"audio\",\"source\":" + source + ",\"variants\":[" + variant + "]}");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        new MediaProcessingService(processingRepository, uploads, objects, fake, mediaGcRepository, processingSettings).process(claim);
    }

    private UUID readyClip(UUID owner) throws Exception {
        UUID asset = UUID.randomUUID();
        byte[] bytes = (new String(WAV, StandardCharsets.US_ASCII) + asset).getBytes(StandardCharsets.US_ASCII);
        stager.stage(owner, asset, MediaCatalog.Kind.AUDIO, "audio/wav", bytes);
        process(processingRepository.claim(asset), "audio/wav");
        assertThat(stager.assetState(owner, asset)).isEqualTo(GeneratedMediaStager.State.READY);
        return asset;
    }

    @Test
    void aWavClipOfTheServerIsStagedProcessedAndReadyWithAWavSourceAndAPlaybackVariant() throws Exception {
        UUID owner = UUID.randomUUID();

        UUID asset = readyClip(owner);

        assertThat(catalog.resolve(owner, asset, null).mimeType()).isEqualTo("audio/wav");
        var playback = catalog.playback(owner, asset);
        assertThat(playback.state()).isEqualTo("READY");
        assertThat(playback.playable().mimeType()).isEqualTo("audio/mp4");
    }

    @Test
    void aWavSourceIsNeverAcceptedForABrowserUpload() throws Exception {
        UUID owner = UUID.randomUUID();
        // the browser declares an allowed type; the bytes are a WAV: the worker says so, and only an asset the server staged may have that source
        var started = uploads.start(owner, UUID.randomUUID(), "upload", "audio", "audio/mpeg", WAV.length);
        var put = java.net.http.HttpRequest.newBuilder(URI.create(started.url())).PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(WAV));
        started.headers().forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length")) put.header(name, value);
        });
        assertThat(HTTP.send(put.build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        uploads.finalizeUpload(owner, started.assetId(), 0, UUID.randomUUID());

        process(processingRepository.claim(started.assetId()), "audio/wav");

        assertThat(jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:asset").param("asset", started.assetId()).query(String.class).single())
                .isEqualTo("REJECTED");
    }

    @Test
    void aWavIsAllowedOnlyThroughTheServerStagingAndOtherTypesStayRefused() {
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> uploads.start(owner, UUID.randomUUID(), "upload", "audio", "audio/wav", 10)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> stager.stage(owner, UUID.randomUUID(), MediaCatalog.Kind.AUDIO, "audio/ogg", WAV)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> stager.stage(owner, UUID.randomUUID(), MediaCatalog.Kind.IMAGE, "audio/wav", WAV)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void anotherOwnerAdoptsTheVerifiedBlobsAsANewReadyAssetWithoutStagingOrProcessing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID asset = readyClip(owner);
        var verified = stager.verified(owner, asset).orElseThrow();
        assertThat(verified.variants()).hasSize(1);
        assertThat(stager.verified(UUID.randomUUID(), asset)).as("another owner has no such asset").isEmpty();
        assertThat(stager.verified(owner, UUID.randomUUID())).isEmpty();

        UUID other = UUID.randomUUID();
        UUID adopted = UUID.randomUUID();
        assertThat(stager.adopt(other, adopted, verified)).isTrue();
        assertThat(stager.adopt(other, adopted, verified)).as("idempotent").isTrue();

        assertThat(stager.assetState(other, adopted)).isEqualTo(GeneratedMediaStager.State.READY);
        assertThat(jdbc.sql("SELECT origin FROM app_learning.media_asset WHERE asset_id=:asset").param("asset", adopted).query(String.class).single()).isEqualTo("generated");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_upload_session WHERE asset_id=:asset").param("asset", adopted).query(Integer.class).single()).isZero();
        assertThat(catalog.resolve(other, adopted, null).blobId()).isEqualTo(catalog.resolve(owner, asset, null).blobId());
        assertThat(catalog.playback(other, adopted).playable().mimeType()).isEqualTo("audio/mp4");
        assertThat(stager.verified(other, adopted).orElseThrow()).isEqualTo(new GeneratedMediaStager.VerifiedMedia(verified.sourceBlob(),
                List.of(new GeneratedMediaStager.VerifiedMedia.Variant("playback", "audio_aac_m4a_v1", verified.variants().getFirst().blob(), null, null, 1_500L))));
        // an asset id that already belongs to someone else is not adoptable
        assertThat(stager.adopt(UUID.randomUUID(), adopted, verified)).isFalse();
        // a blob that is gone is not adoptable either
        assertThat(stager.adopt(other, UUID.randomUUID(), new GeneratedMediaStager.VerifiedMedia(UUID.randomUUID(), List.of()))).isFalse();
    }

    @Test
    void aBlobTheGcIsAlreadyReclaimingIsNotAdoptedAndOneItOnlyScannedIsPinnedAgain() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID asset = readyClip(owner);
        var verified = stager.verified(owner, asset).orElseThrow();
        String key = jdbc.sql("SELECT object_key FROM app_learning.media_blob WHERE blob_id=:blob").param("blob", verified.sourceBlob()).query(String.class).single();
        mediaGcRepository.discover(10_000);
        jdbc.sql("UPDATE app_learning.media_gc_object SET state='FIRST',first_scan_at=CURRENT_TIMESTAMP,first_scan_epoch=0 WHERE object_key=:key").param("key", key).update();

        assertThat(stager.adopt(UUID.randomUUID(), UUID.randomUUID(), verified)).isTrue();
        assertThat(jdbc.sql("SELECT state FROM app_learning.media_gc_object WHERE object_key=:key").param("key", key).query(String.class).single()).isEqualTo("TRACKED");

        jdbc.sql("UPDATE app_learning.media_gc_object SET state='DELETING',delete_token=gen_random_uuid(),lease_until=CURRENT_TIMESTAMP + interval '1 hour',"
                + "first_scan_at=CURRENT_TIMESTAMP WHERE object_key=:key").param("key", key).update();
        UUID refused = UUID.randomUUID();
        assertThat(stager.adopt(UUID.randomUUID(), refused, verified)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_asset WHERE asset_id=:asset").param("asset", refused).query(Integer.class).single()).isZero();
    }

    @Test
    void theSpeechCacheKeepsItsBlobsOutOfTheMediaGcUntilItsEntryIsGone() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID asset = readyClip(owner);
        var verified = stager.verified(owner, asset).orElseThrow();
        String key = jdbc.sql("SELECT object_key FROM app_learning.media_blob WHERE blob_id=:blob").param("blob", verified.variants().getFirst().blob()).query(String.class).single();
        // the owner's asset is gone and nothing else reaches the blobs ...
        jdbc.sql("UPDATE app_learning.media_asset SET owner_hold_until=CURRENT_TIMESTAMP - interval '1 hour' WHERE asset_id=:asset").param("asset", asset).update();
        assertThat(catalogExpire()).isGreaterThanOrEqualTo(1);
        mediaGcRepository.discover(10_000);
        mediaGcRepository.scanKey(key, 0);
        assertThat(gcState(key)).as("unreachable blobs are marked for the first scan").isEqualTo("FIRST");
        jdbc.sql("UPDATE app_learning.media_gc_object SET state='TRACKED',first_scan_at=NULL,first_scan_epoch=NULL WHERE object_key=:key").param("key", key).update();

        // ... except through a READY entry of the speech cache
        UUID cache = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.speech_cache(cache_key,provider,model,voice,lang,take,state,verified,blob_ids,created_at,last_used_at) VALUES "
                        + "(:key,'stub','m','female','en',0,'READY',CAST('{}' AS jsonb),CAST(:blobs AS uuid[]),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("key", HexFormat.of().parseHex(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cache.toString().getBytes(StandardCharsets.UTF_8)))))
                .param("blobs", "{" + verified.sourceBlob() + "," + verified.variants().getFirst().blob() + "}").update();
        mediaGcRepository.scanKey(key, 0);
        assertThat(gcState(key)).as("held by the cache").isEqualTo("TRACKED");

        jdbc.sql("DELETE FROM app_learning.speech_cache").update();
        mediaGcRepository.scanKey(key, 0);
        assertThat(gcState(key)).isEqualTo("FIRST");
    }

    private int catalogExpire() { return catalog.expireUnattached(1_000); }

    private String gcState(String key) {
        return jdbc.sql("SELECT state FROM app_learning.media_gc_object WHERE object_key=:key").param("key", key).query(String.class).single();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
