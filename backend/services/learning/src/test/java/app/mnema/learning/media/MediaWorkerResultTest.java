package app.mnema.learning.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The worker is untrusted: its result is read once, without following links, and copied into private storage. */
class MediaWorkerResultTest {
    @TempDir Path root;
    private Path output;
    private Path scratch;
    private final UUID asset = UUID.randomUUID();

    @BeforeEach
    void directories() throws IOException {
        output = Files.createDirectory(root.resolve("output"));
    }

    /** Each call gets a fresh private directory, as each processing run does. */
    private MediaWorkerResult.Verified read() {
        try {
            scratch = Files.createTempDirectory(root, "scratch-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        return MediaWorkerResult.read(output, asset, 0, "image", 3, sha(new byte[]{9, 8, 7}), 0, scratch);
    }

    @Test
    void acceptsAnimatedGifOnlyWithAStaticPosterProfileAndCopiesTheVerifiedBytesPrivately() throws IOException {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_gif_poster_webp_320_v1", "image/webp");

        var result = read();

        assertThat(result.variants()).extracting(MediaWorkerResult.Variant::profile)
                .containsExactly("image_gif_2048_v1", "image_gif_poster_webp_320_v1");
        assertThat(result.variants()).allSatisfy(variant -> assertThat(variant.path().getParent()).isEqualTo(scratch));
        assertThat(Files.readAllBytes(scratch.resolve("image_gif_2048_v1.gif"))).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsUnexpectedAnimatedThumbnailAndChangedBytes() throws IOException {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        Path poster = write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_webp_320_v1", "image/webp");
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);

        writeManifest("image_gif_poster_webp_320_v1", "image/webp");
        Files.write(poster, new byte[]{6, 5, 4});
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVariantLongerOrShorterThanDeclaredIsRefused() throws IOException {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        Path poster = write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_gif_poster_webp_320_v1", "image/webp");
        Files.write(poster, new byte[]{4, 5, 6, 7});
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
        Files.write(poster, new byte[]{4, 5});
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSymbolicLinkInPlaceOfTheManifestOrAVariantIsNeverFollowed() throws IOException {
        Path secret = Files.write(root.resolve("secret"), new byte[]{4, 5, 6});
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        Path poster = write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_gif_poster_webp_320_v1", "image/webp");
        assertThat(read().variants()).hasSize(2);       // the honest layout passes

        Files.delete(poster);
        Files.createSymbolicLink(poster, secret);       // same bytes and hash, but reached through a link
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
        Files.delete(poster);
        write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});

        Path manifest = output.resolve("result.json");
        Path real = Files.move(manifest, root.resolve("real-result.json"));
        Files.createSymbolicLink(manifest, real);
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOutputDirectoryThatIsALinkIsRefused() throws IOException {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_gif_poster_webp_320_v1", "image/webp");
        Path moved = Files.move(output, root.resolve("moved"));
        Files.createSymbolicLink(output, moved);
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aFifoInPlaceOfTheManifestOrAVariantIsRefusedWithoutBlocking() throws Exception {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_gif_poster_webp_320_v1", "image/webp");
        Path poster = output.resolve("image_gif_poster_webp_320_v1.webp");
        Files.delete(poster);
        mkfifo(poster);
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
        Files.delete(poster);
        write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        Files.delete(output.resolve("result.json"));
        mkfifo(output.resolve("result.json"));
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOversizedManifestIsRefused() throws IOException {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_gif_poster_webp_320_v1", "image/webp");
        Files.writeString(output.resolve("result.json"), " ".repeat(70_000), java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class);
    }

    static void mkfifo(Path path) throws Exception {
        Process process = new ProcessBuilder("mkfifo", path.toString()).inheritIO().start();
        assumeTrue(process.waitFor() == 0, "mkfifo is required");
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path path = output.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private void writeManifest(String posterProfile, String posterMime) throws IOException {
        String source = "{\"sha256\":\"" + sha(new byte[]{9, 8, 7})
                + "\",\"byteLength\":3,\"mimeType\":\"image/gif\",\"durationMs\":80,"
                + "\"width\":20,\"height\":20}";
        String playback = variant("playback", "image_gif_2048_v1", "image_gif_2048_v1.gif",
                "image/gif", new byte[]{1, 2, 3}, 80);
        String poster = variant("thumbnail", posterProfile, "image_gif_poster_webp_320_v1.webp",
                posterMime, new byte[]{4, 5, 6}, 40);
        Files.writeString(output.resolve("result.json"), "{\"formatVersion\":1,\"assetId\":\""
                + asset + "\",\"generation\":0,\"kind\":\"image\",\"source\":" + source
                + ",\"variants\":[" + playback + "," + poster + "]}");
    }

    private static String variant(String purpose, String profile, String path, String mime, byte[] bytes,
                                  long duration) {
        return "{\"purpose\":\"" + purpose + "\",\"profile\":\"" + profile
                + "\",\"path\":\"" + path + "\",\"sha256\":\"" + sha(bytes)
                + "\",\"byteLength\":" + bytes.length + ",\"mimeType\":\"" + mime
                + "\",\"durationMs\":" + duration + ",\"width\":20,\"height\":20}";
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
