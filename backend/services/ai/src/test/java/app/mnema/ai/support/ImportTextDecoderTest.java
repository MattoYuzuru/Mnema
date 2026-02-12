package app.mnema.ai.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportTextDecoderTest {

    @Test
    void decodeUtf8Bom() {
        byte[] bytes = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        ImportTextDecoder.DecodedText decoded = ImportTextDecoder.decode(bytes, null);
        assertEquals("hi", decoded.text());
        assertEquals(StandardCharsets.UTF_8, decoded.charset());
    }

    @Test
    void decodeUtf16LeBom() {
        byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xFE, 'h', 0x00, 'i', 0x00};
        ImportTextDecoder.DecodedText decoded = ImportTextDecoder.decode(bytes, null);
        assertEquals("hi", decoded.text());
        assertEquals(StandardCharsets.UTF_16LE, decoded.charset());
    }

    @Test
    void decodeWithExplicitEncoding() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);
        ImportTextDecoder.DecodedText decoded = ImportTextDecoder.decode(bytes, "UTF-8");
        assertEquals("test", decoded.text());
        assertEquals(StandardCharsets.UTF_8, decoded.charset());
    }

    @Test
    void decodeUtf8WithoutBomAuto() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        ImportTextDecoder.DecodedText decoded = ImportTextDecoder.decode(bytes, null);
        assertEquals("hello", decoded.text());
        assertEquals(StandardCharsets.UTF_8, decoded.charset());
    }

    @Test
    void decodeCyrillicAutoCp1251() {
        Charset cp1251 = Charset.forName("windows-1251");
        String message = "\u041f\u0440\u0438\u0432\u0435\u0442 \u043c\u0438\u0440";
        byte[] bytes = message.getBytes(cp1251);
        ImportTextDecoder.DecodedText decoded = ImportTextDecoder.decode(bytes, null);
        assertEquals(message, decoded.text());
        assertEquals(cp1251, decoded.charset());
    }

    @Test
    void invalidEncodingThrows() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> ImportTextDecoder.decode(bytes, "unknown-charset"));
    }
}
