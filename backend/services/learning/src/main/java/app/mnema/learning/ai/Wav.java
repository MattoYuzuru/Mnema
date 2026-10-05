package app.mnema.learning.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** The one audio container Gemini returns (RIFF/WAVE, PCM signed 16-bit little-endian): a strict reader of its header and a writer for the Stub. */
final class Wav {
    private Wav() { }

    /** @param durationMs rounded up */
    record Info(int sampleRate, int channels, long dataBytes, long durationMs) { }

    /** The header facts of a PCM s16le WAV, or null when the bytes are anything else (the pipeline would reject them too). */
    static Info parse(byte[] bytes) {
        if (bytes.length < 44 || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE")) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int sampleRate = 0;
        int channels = 0;
        boolean format = false;
        int position = 12;
        while (position + 8 <= bytes.length) {
            long size = buffer.getInt(position + 4) & 0xffffffffL;
            int body = position + 8;
            if (tag(bytes, position, "fmt ")) {
                if (size < 16 || body + 16 > bytes.length) return null;
                int code = buffer.getShort(body) & 0xffff;
                channels = buffer.getShort(body + 2) & 0xffff;
                sampleRate = buffer.getInt(body + 4);
                int bits = buffer.getShort(body + 14) & 0xffff;
                if ((code != 1 && code != 0xfffe) || bits != 16 || channels < 1 || channels > 2 || sampleRate < 8_000 || sampleRate > 96_000) return null;
                format = true;
            } else if (tag(bytes, position, "data")) {
                if (!format || size == 0 || body + size > bytes.length) return null;
                long frames = size / (2L * channels);
                return new Info(sampleRate, channels, size, (frames * 1000 + sampleRate - 1) / sampleRate);
            }
            long next = body + size + (size & 1);
            if (next > bytes.length) return null;
            position = (int) next;
        }
        return null;
    }

    /** A mono 16-bit WAV of {@code samples} (the values are the caller's). */
    static byte[] write(short[] samples, int sampleRate) {
        int data = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(sampleRate)
                .putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
        for (short sample : samples) buffer.putShort(sample);
        return buffer.array();
    }

    private static boolean tag(byte[] bytes, int offset, String tag) {
        for (int index = 0; index < 4; index++) if (bytes[offset + index] != (byte) tag.charAt(index)) return false;
        return true;
    }
}
