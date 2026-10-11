package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The opaque keyset position of an owner's list (invitations, requests, grants): the time and the id of the last row of the previous page, newest first.
 * It carries no authority (the owner is checked again on every request) and any malformed text is the 400 of the house schema.
 */
record AccessCursor(Instant time, UUID id) {
    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 100;

    String encode() {
        long micros = Math.addExact(Math.multiplyExact(time.getEpochSecond(), 1_000_000L), time.getNano() / 1_000L);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((micros + "/" + id).getBytes(StandardCharsets.US_ASCII));
    }

    /** @return the cursor, or null for the first page */
    static AccessCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 96 || !encoded.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
            if (parts.length != 2 || !parts[0].matches("[1-9][0-9]{0,17}") || parts[1].length() != 36) throw new InvalidRequestException();
            long micros = Long.parseLong(parts[0]);
            AccessCursor cursor = new AccessCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L),
                    UuidPolicy.requireEntityId(UUID.fromString(parts[1]), "id"));
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new InvalidRequestException();
        }
    }

    /** {@code limit} as a query parameter text: absent is 20, 1..100 otherwise. */
    static int pageSize(String value) {
        if (value == null) return DEFAULT_PAGE;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new InvalidRequestException();
        int size = Integer.parseInt(value);
        if (size > MAX_PAGE) throw new InvalidRequestException();
        return size;
    }
}
