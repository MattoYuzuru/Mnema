package app.mnema.learning.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/**
 * Deterministic image search for local runs and CI: no network, no key. It exists only with {@code learning.ai.provider=stub}. A query gets
 * 4 to 6 candidates (a function of the query), every one source {@code STUB}, license {@code CC0 1.0}, page {@code https://example.org/stub/<n>};
 * {@link #fetch} draws a small PNG in-process (a distinct colour per candidate, no image library). Two markers in the query simulate failures:
 * {@code [[stub:image-none]]} (nothing licensed was found) and {@code [[stub:image-down]]} (every source is down).
 */
final class StubImageSearch implements ImageSearch {
    static final String NONE = "[[stub:image-none]]";
    static final String DOWN = "[[stub:image-down]]";
    private static final String SCHEME = "stub:";

    @Override
    public AiResult<List<Candidate>> search(Request request) {
        if (request.query().contains(DOWN)) return AiResult.failed(new AiFailure.Transient("stub_down"));
        if (request.query().contains(NONE)) return AiResult.ok(List.of());
        byte[] digest = sha256(request.query());
        String seed = HexFormat.of().formatHex(digest, 0, 4);
        int count = 4 + Math.floorMod(digest[4], 3);
        List<Candidate> out = new ArrayList<>();
        for (int number = 1; number <= count && out.size() < request.maxResults(); number++) {
            Candidate candidate = new Candidate(Source.STUB, seed + "-" + number, "Stub image " + number, "Stub Author", "CC0 1.0",
                    "https://creativecommons.org/publicdomain/zero/1.0/", "https://example.org/stub/" + number, false, 64, 48,
                    SCHEME + seed + ":" + number);
            if (!request.excludeKeys().contains(candidate.key())) out.add(candidate);
        }
        return AiResult.ok(out);
    }

    @Override
    public AiResult<Image> fetch(Candidate candidate) {
        String url = candidate.downloadUrl();
        if (!url.startsWith(SCHEME)) return AiResult.failed(new AiFailure.Refusal("not_a_stub_candidate"));
        String[] parts = url.substring(SCHEME.length()).split(":");
        if (parts.length != 2) return AiResult.failed(new AiFailure.Refusal("not_a_stub_candidate"));
        byte[] digest = sha256(parts[0] + ":" + parts[1]);
        return AiResult.ok(new Image(png(64, 48, digest), "image/png"));
    }

    /** An 8-bit truecolor PNG: a background colour from the digest and a stripe in another, so no two candidates are the same bytes. */
    static byte[] png(int width, int height, byte[] digest) {
        int[] background = {digest[0] & 0xff, digest[1] & 0xff, digest[2] & 0xff};
        int[] stripe = {digest[3] & 0xff, digest[4] & 0xff, digest[5] & 0xff};
        int stripeRow = Math.floorMod(digest[6], height);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(raw)) {
            for (int row = 0; row < height; row++) {
                deflate.write(0);
                int[] colour = row == stripeRow ? stripe : background;
                for (int column = 0; column < width; column++) for (int channel : colour) deflate.write(channel);
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
        byte[] header = new byte[13];
        put(header, 0, width);
        put(header, 4, height);
        header[8] = 8;
        header[9] = 2;
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", raw.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        byte[] length = new byte[4];
        put(length, 0, data.length);
        out.writeBytes(length);
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] check = new byte[4];
        put(check, 0, (int) crc.getValue());
        out.writeBytes(check);
    }

    private static void put(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
