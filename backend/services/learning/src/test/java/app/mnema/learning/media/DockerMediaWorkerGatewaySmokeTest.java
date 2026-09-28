package app.mnema.learning.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Local-only contract check of host bind mounts, UID mapping and the actual worker image. */
@EnabledIfEnvironmentVariable(named = "MNEMA_MEDIA_DOCKER_SMOKE", matches = "1")
class DockerMediaWorkerGatewaySmokeTest {
    @Test
    void animatedGifProducesAStaticPosterThroughTheDockerBoundary() throws Exception {
        Path root = Path.of(System.getProperty("user.home"), ".mnema", "media-processing");
        Files.createDirectories(root);
        Path job = Files.createTempDirectory(root, "smoke-");
        try {
            Path source = job.resolve("source");
            var fixture = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                    "testsrc2=s=96x72:r=5:d=0.4", "-c:v", "gif", "-f", "gif", source.toString())
                    .inheritIO().start();
            assertThat(fixture.waitFor()).isZero();
            String sha;
            try (InputStream input = Files.newInputStream(source)) {
                sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            }
            var settings = new MediaProcessingSettings(true, root.toString(), "docker",
                    "mnema-media-worker:local", Duration.ofMinutes(30), Duration.ofMinutes(2),
                    Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(30), 5, 2,
                    Duration.ofHours(1), Duration.ofMinutes(5));
            UUID asset = UUID.randomUUID();
            new DockerMediaWorkerGateway(settings).run(job, asset, 0, "image", Files.size(source), sha, 0);
            var result = MediaWorkerResult.read(job.resolve("output"), asset, 0, "image",
                    Files.size(source), sha, 0);
            assertThat(result.variants()).extracting(MediaWorkerResult.Variant::profile)
                    .containsExactly("image_gif_2048_v1", "image_gif_poster_webp_320_v1");
        } finally {
            try (var paths = Files.walk(job)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
