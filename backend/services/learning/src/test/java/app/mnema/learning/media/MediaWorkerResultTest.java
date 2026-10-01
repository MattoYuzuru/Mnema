package app.mnema.learning.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaWorkerResultTest {
    @TempDir Path directory;
    private final UUID asset = UUID.randomUUID();

    @Test
    void acceptsAnimatedGifOnlyWithAStaticPosterProfile() throws IOException {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_gif_poster_webp_320_v1", "image/webp");

        var result = MediaWorkerResult.read(directory, asset, 0, "image", 3, sha(new byte[]{9, 8, 7}), 0);

        assertThat(result.variants()).extracting(MediaWorkerResult.Variant::profile)
                .containsExactly("image_gif_2048_v1", "image_gif_poster_webp_320_v1");
    }

    @Test
    void rejectsUnexpectedAnimatedThumbnailAndChangedBytes() throws IOException {
        write("image_gif_2048_v1.gif", new byte[]{1, 2, 3});
        Path poster = write("image_gif_poster_webp_320_v1.webp", new byte[]{4, 5, 6});
        writeManifest("image_webp_320_v1", "image/webp");
        assertThatThrownBy(() -> MediaWorkerResult.read(directory, asset, 0, "image", 3,
                sha(new byte[]{9, 8, 7}), 0)).isInstanceOf(IllegalArgumentException.class);

        writeManifest("image_gif_poster_webp_320_v1", "image/webp");
        Files.write(poster, new byte[]{6, 5, 4});
        assertThatThrownBy(() -> MediaWorkerResult.read(directory, asset, 0, "image", 3,
                sha(new byte[]{9, 8, 7}), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path path = directory.resolve(name);
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
        Files.writeString(directory.resolve("result.json"), "{\"formatVersion\":1,\"assetId\":\""
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
