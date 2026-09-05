package app.mnema.identityaccount.transfer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;

final class AvatarBinary {
    private AvatarBinary() {
    }

    static void validate(AccountTransferBundle.Avatar avatar, byte[] bytes) {
        AccountTransferBundle.require(bytes.length == avatar.byteSize(), "avatar_size_mismatch");
        AccountTransferBundle.require(sha256(bytes).equals(avatar.contentSha256()), "avatar_hash_mismatch");
        int[] dimensions = dimensions(avatar.contentType(), bytes);
        AccountTransferBundle.require(dimensions[0] >= 1 && dimensions[0] <= 1024
                        && dimensions[1] >= 1 && dimensions[1] <= 1024,
                "invalid_avatar_dimensions");
        if (avatar.width() != null)
            AccountTransferBundle.require(avatar.width() == dimensions[0] && avatar.height() == dimensions[1],
                    "avatar_dimension_mismatch");
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int[] dimensions(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> decoded(bytes, png(bytes));
            case "image/jpeg" -> decoded(bytes, jpeg(bytes));
            case "image/webp" -> webp(bytes);
            default -> throw new AccountTransferFailure("invalid_avatar_content_type");
        };
    }

    private static int[] decoded(byte[] bytes, int[] headerDimensions) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            AccountTransferBundle.require(image != null && image.getWidth() == headerDimensions[0]
                    && image.getHeight() == headerDimensions[1], "invalid_avatar_blob");
            return headerDimensions;
        } catch (IOException exception) {
            throw new AccountTransferFailure("invalid_avatar_blob", exception);
        }
    }

    private static int[] png(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        AccountTransferBundle.require(bytes.length >= 24, "invalid_avatar_blob");
        for (int index = 0; index < signature.length; index++)
            AccountTransferBundle.require(bytes[index] == signature[index], "invalid_avatar_blob");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return new int[]{buffer.getInt(16), buffer.getInt(20)};
    }

    private static int[] jpeg(byte[] bytes) {
        AccountTransferBundle.require(bytes.length >= 4 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8,
                "invalid_avatar_blob");
        int offset = 2;
        while (offset + 3 < bytes.length) {
            while (offset < bytes.length && unsigned(bytes[offset]) != 0xff) offset++;
            while (offset < bytes.length && unsigned(bytes[offset]) == 0xff) offset++;
            AccountTransferBundle.require(offset < bytes.length, "invalid_avatar_blob");
            int marker = unsigned(bytes[offset++]);
            if (marker == 0xd8 || marker == 0xd9 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            AccountTransferBundle.require(offset + 1 < bytes.length, "invalid_avatar_blob");
            int length = (unsigned(bytes[offset]) << 8) | unsigned(bytes[offset + 1]);
            AccountTransferBundle.require(length >= 2 && offset + length <= bytes.length, "invalid_avatar_blob");
            if (SetOfFrames.contains(marker)) {
                AccountTransferBundle.require(length >= 7, "invalid_avatar_blob");
                int height = (unsigned(bytes[offset + 3]) << 8) | unsigned(bytes[offset + 4]);
                int width = (unsigned(bytes[offset + 5]) << 8) | unsigned(bytes[offset + 6]);
                return new int[]{width, height};
            }
            offset += length;
        }
        throw new AccountTransferFailure("invalid_avatar_blob");
    }

    private static int[] webp(byte[] bytes) {
        AccountTransferBundle.require(bytes.length >= 16 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP"),
                "invalid_avatar_blob");
        String chunk = new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if ("VP8X".equals(chunk)) {
            AccountTransferBundle.require(bytes.length >= 30, "invalid_avatar_blob");
            return new int[]{1 + little24(bytes, 24), 1 + little24(bytes, 27)};
        }
        if ("VP8L".equals(chunk)) {
            AccountTransferBundle.require(bytes.length >= 25 && unsigned(bytes[20]) == 0x2f, "invalid_avatar_blob");
            int b1 = unsigned(bytes[21]), b2 = unsigned(bytes[22]), b3 = unsigned(bytes[23]), b4 = unsigned(bytes[24]);
            return new int[]{1 + b1 + ((b2 & 0x3f) << 8), 1 + ((b2 & 0xc0) >> 6) + (b3 << 2) + ((b4 & 0x0f) << 10)};
        }
        if ("VP8 ".equals(chunk)) {
            AccountTransferBundle.require(bytes.length >= 30 && unsigned(bytes[23]) == 0x9d
                    && unsigned(bytes[24]) == 0x01 && unsigned(bytes[25]) == 0x2a, "invalid_avatar_blob");
            return new int[]{little16(bytes, 26) & 0x3fff, little16(bytes, 28) & 0x3fff};
        }
        throw new AccountTransferFailure("invalid_avatar_blob");
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        if (bytes.length < offset + expected.length()) return false;
        for (int index = 0; index < expected.length(); index++)
            if (bytes[offset + index] != expected.charAt(index)) return false;
        return true;
    }

    private static int little16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    private static int little24(byte[] bytes, int offset) {
        return little16(bytes, offset) | (unsigned(bytes[offset + 2]) << 16);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static final class SetOfFrames {
        private SetOfFrames() {
        }

        static boolean contains(int marker) {
            return (marker >= 0xc0 && marker <= 0xc3)
                    || (marker >= 0xc5 && marker <= 0xc7)
                    || (marker >= 0xc9 && marker <= 0xcb)
                    || (marker >= 0xcd && marker <= 0xcf);
        }
    }
}
