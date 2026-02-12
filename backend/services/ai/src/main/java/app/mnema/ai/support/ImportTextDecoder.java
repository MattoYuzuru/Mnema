package app.mnema.ai.support;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class ImportTextDecoder {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final int SAMPLE_LIMIT = 64 * 1024;

    private ImportTextDecoder() {
    }

    public static DecodedText decode(byte[] bytes, String encoding) {
        if (bytes == null || bytes.length == 0) {
            return new DecodedText("", StandardCharsets.UTF_8);
        }
        Charset charset = resolveCharset(encoding, bytes);
        int offset = bomOffset(bytes, charset);
        String text = new String(bytes, offset, bytes.length - offset, charset);
        return new DecodedText(text, charset);
    }

    private static Charset resolveCharset(String encoding, byte[] bytes) {
        if (encoding != null && !encoding.isBlank()) {
            try {
                return Charset.forName(encoding.trim());
            } catch (Exception ex) {
                throw new IllegalStateException("Unsupported encoding: " + encoding);
            }
        }
        BomCharset bom = detectBom(bytes);
        if (bom.offset() > 0) {
            return bom.charset();
        }
        if (looksLikeUtf8(bytes)) {
            return StandardCharsets.UTF_8;
        }
        Charset utf16 = detectUtf16(bytes);
        if (utf16 != null) {
            return utf16;
        }
        if (looksLikeCyrillic(bytes)) {
            return WINDOWS_1251;
        }
        return StandardCharsets.ISO_8859_1;
    }

    private static BomCharset detectBom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new BomCharset(StandardCharsets.UTF_8, 3);
        }
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x00
                && (bytes[1] & 0xFF) == 0x00
                && (bytes[2] & 0xFF) == 0xFE
                && (bytes[3] & 0xFF) == 0xFF) {
            return new BomCharset(Charset.forName("UTF-32BE"), 4);
        }
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE
                && (bytes[2] & 0xFF) == 0x00
                && (bytes[3] & 0xFF) == 0x00) {
            return new BomCharset(Charset.forName("UTF-32LE"), 4);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            return new BomCharset(StandardCharsets.UTF_16BE, 2);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            return new BomCharset(StandardCharsets.UTF_16LE, 2);
        }
        return new BomCharset(StandardCharsets.UTF_8, 0);
    }

    private static int bomOffset(byte[] bytes, Charset charset) {
        BomCharset bom = detectBom(bytes);
        if (bom.offset() > 0 && bom.charset().equals(charset)) {
            return bom.offset();
        }
        return 0;
    }

    private static boolean looksLikeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int length = Math.min(bytes.length, SAMPLE_LIMIT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, length));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static Charset detectUtf16(byte[] bytes) {
        int length = Math.min(bytes.length, 2048);
        if (length < 4) {
            return null;
        }
        int evenZeros = 0;
        int oddZeros = 0;
        for (int i = 0; i < length; i++) {
            if (bytes[i] == 0) {
                if ((i & 1) == 0) {
                    evenZeros++;
                } else {
                    oddZeros++;
                }
            }
        }
        int pairs = length / 2;
        int threshold = Math.max(2, pairs / 4);
        if (evenZeros > oddZeros * 2 && evenZeros > threshold) {
            return StandardCharsets.UTF_16BE;
        }
        if (oddZeros > evenZeros * 2 && oddZeros > threshold) {
            return StandardCharsets.UTF_16LE;
        }
        return null;
    }

    private static boolean looksLikeCyrillic(byte[] bytes) {
        int length = Math.min(bytes.length, 4096);
        if (length == 0) {
            return false;
        }
        String sample = new String(bytes, 0, length, WINDOWS_1251);
        int letters = 0;
        int cyrillic = 0;
        for (int i = 0; i < sample.length(); i++) {
            char ch = sample.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (ch >= '\u0400' && ch <= '\u04FF') {
                    cyrillic++;
                }
            }
        }
        if (letters == 0) {
            return false;
        }
        return cyrillic >= 5 && cyrillic >= Math.max(2, letters / 5);
    }

    public record DecodedText(String text, Charset charset) {
    }

    private record BomCharset(Charset charset, int offset) {
    }
}
