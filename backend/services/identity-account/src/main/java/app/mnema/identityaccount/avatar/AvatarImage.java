package app.mnema.identityaccount.avatar;

import app.mnema.identityaccount.contract.AccountFailure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

public record AvatarImage(byte[] bytes, String contentType, int width, int height) {
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public static AvatarImage read(InputStream input, String declared) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new AccountFailure(413, "avatar_too_large");
        if (!Set.of("image/png", "image/jpeg", "image/webp").contains(Objects.toString(declared, "")))
            throw new AccountFailure(415, "invalid_avatar");
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new AccountFailure(415, "invalid_avatar");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String actual = switch (format) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "webp" -> "image/webp";
                    default -> "";
                };
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!actual.equals(declared) || width < 1 || height < 1 || width > 1024 || height > 1024)
                    throw new AccountFailure(415, "invalid_avatar");
                if (reader.read(0) == null) throw new AccountFailure(415, "invalid_avatar");
                return new AvatarImage(bytes, actual, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new AccountFailure(415, "invalid_avatar");
        }
    }
}
