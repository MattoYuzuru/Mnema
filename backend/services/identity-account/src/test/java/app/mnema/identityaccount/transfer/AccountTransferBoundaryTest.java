package app.mnema.identityaccount.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTransferBoundaryTest {
    private static final UUID ASSET = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @TempDir
    Path temporary;

    @Test
    void validatesJpegAndEverySupportedWebpHeader() {
        validate("image/jpeg", jpeg());
        validate("image/webp", webpExtended());
        validate("image/webp", webpLossless());
        validate("image/webp", webpLossy());
    }

    @Test
    void rejectsCorruptMismatchedAndOversizedImages() {
        byte[] png = png(1, 1);
        var wrongHash = avatar("image/png", png, 1, 1, "0".repeat(64));
        var wrongDimensions = avatar("image/png", png, 2, 1, AvatarBinary.sha256(png));
        var oversizedDimensions = avatar("image/png", png(1025, 1), null, null,
                AvatarBinary.sha256(png(1025, 1)));
        var wrongType = avatar("image/png", jpeg(), 1, 1, AvatarBinary.sha256(jpeg()));

        assertThatThrownBy(() -> AvatarBinary.validate(wrongHash, png)).hasMessage("avatar_hash_mismatch");
        assertThatThrownBy(() -> AvatarBinary.validate(wrongDimensions, png)).hasMessage("avatar_dimension_mismatch");
        assertThatThrownBy(() -> AvatarBinary.validate(oversizedDimensions, png(1025, 1)))
                .hasMessage("invalid_avatar_dimensions");
        assertThatThrownBy(() -> AvatarBinary.validate(wrongType, jpeg())).hasMessage("invalid_avatar_blob");
        assertThatThrownBy(() -> validate("image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, 0, 0}))
                .hasMessage("invalid_avatar_blob");
        assertThatThrownBy(() -> validate("image/webp", new byte[30])).hasMessage("invalid_avatar_blob");
    }

    @Test
    void directoryStoreIsIdempotentAndRejectsConflictsAndTraversal() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("objects"));
        DirectoryAvatarBlobStore store = new DirectoryAvatarBlobStore(root);
        byte[] value = {1, 2, 3};

        assertThat(store.putExact("account-avatar/a/b", value)).isTrue();
        assertThat(store.putExact("account-avatar/a/b", value)).isFalse();
        assertThat(store.read("account-avatar/a/b")).containsExactly(value);
        assertThatThrownBy(() -> store.putExact("account-avatar/a/b", new byte[]{9}))
                .hasMessage("avatar_object_conflict");
        assertThatThrownBy(() -> store.read("../secret")).hasMessage("invalid_avatar_object_key");
        assertThatThrownBy(() -> store.read("missing")).hasMessage("avatar_object_missing");
        store.deleteExact("account-avatar/a/b");
        assertThat(root.resolve("account-avatar/a/b")).doesNotExist();
    }

    @Test
    void directoryStoreRejectsSymlinkAncestorsWithoutExternalMutation() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("objects"));
        Path external = Files.createDirectory(temporary.resolve("external"));
        Files.createSymbolicLink(root.resolve("escape"), external);
        DirectoryAvatarBlobStore store = new DirectoryAvatarBlobStore(root);

        assertThatThrownBy(() -> store.putExact("escape/created/avatar", new byte[]{1, 2, 3}))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("invalid_avatar_object_path");
        assertThat(external.resolve("created")).doesNotExist();

        Path preserved = Files.write(external.resolve("preserved-avatar"), new byte[]{4, 5, 6});
        assertThatThrownBy(() -> store.deleteExact("escape/preserved-avatar"))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("invalid_avatar_object_path");
        assertThat(preserved).exists().hasBinaryContent(new byte[]{4, 5, 6});
    }

    @Test
    void codecRejectsWrongKeyTamperingAndExistingOutput() throws IOException {
        AccountTransferCodec codec = new AccountTransferCodec(new byte[32]);
        AccountTransferArtifact empty = new AccountTransferArtifact(
                new AccountTransferBundle(1, AccountTransferBundle.KIND, java.util.List.of()), java.util.Map.of());
        Path artifact = temporary.resolve("empty.enc");
        codec.write(artifact, empty);

        assertThatThrownBy(() -> codec.write(artifact, empty)).hasMessage("artifact_exists");
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        assertThatThrownBy(() -> new AccountTransferCodec(otherKey).read(artifact))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("artifact_authentication_failed");
        byte[] tampered = Files.readAllBytes(artifact);
        tampered[tampered.length - 1] ^= 1;
        Path tamperedArtifact = temporary.resolve("tampered.enc");
        Files.write(tamperedArtifact, tampered);
        assertThatThrownBy(() -> codec.read(tamperedArtifact))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("artifact_authentication_failed");
        assertThatThrownBy(() -> new AccountTransferCodec(new byte[31])).hasMessage("invalid_encryption_key");
    }

    private static void validate(String type, byte[] bytes) {
        AccountTransferBundle.Avatar avatar = avatar(type, bytes, 1, 1, AvatarBinary.sha256(bytes));
        AvatarBinary.validate(avatar, bytes);
    }

    private static AccountTransferBundle.Avatar avatar(
            String type, byte[] bytes, Integer width, Integer height, String hash) {
        return new AccountTransferBundle.Avatar(ASSET, type, bytes.length, hash, width, height, Instant.EPOCH);
    }

    private static byte[] png(int width, int height) {
        return image("png", width, height);
    }

    private static byte[] jpeg() {
        return image("jpeg", 1, 1);
    }

    private static byte[] image(String format, int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) throw new IllegalStateException("missing image writer");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] webpExtended() {
        byte[] bytes = webp("VP8X", 30);
        return bytes;
    }

    private static byte[] webpLossless() {
        byte[] bytes = webp("VP8L", 25);
        bytes[20] = 0x2f;
        return bytes;
    }

    private static byte[] webpLossy() {
        byte[] bytes = webp("VP8 ", 30);
        bytes[23] = (byte) 0x9d;
        bytes[24] = 0x01;
        bytes[25] = 0x2a;
        bytes[26] = 1;
        bytes[28] = 1;
        return bytes;
    }

    private static byte[] webp(String chunk, int size) {
        byte[] bytes = new byte[size];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        System.arraycopy(chunk.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        return bytes;
    }
}
