package app.mnema.learning.generation;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque position in the newest-first session list: the last row's {@code (lastActivityAt, sessionId)}. */
record ListCursor(Instant lastActivityAt, UUID sessionId) {
    String encode() {
        long micros = Math.addExact(Math.multiplyExact(lastActivityAt.getEpochSecond(), 1_000_000L), lastActivityAt.getNano() / 1_000);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((micros + "/" + sessionId).getBytes(StandardCharsets.US_ASCII));
    }

    /** @throws InvalidRequestException anything that {@link #encode} would not have produced */
    static ListCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 128 || !encoded.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
            if (parts.length != 2 || !parts[0].matches("0|[1-9][0-9]{0,17}")) throw new InvalidRequestException();
            long micros = Long.parseLong(parts[0]);
            UUID id = UUID.fromString(parts[1]);
            ListCursor cursor = new ListCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000), id);
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new InvalidRequestException();
        }
    }
}
