package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * The opaque position in a manifest page list: the kind of list, the published revision it was cut from and the next ordinal. Bound to the revision
 * so a page list cannot mix two publications; it grants nothing (the deck is resolved again on every request).
 */
record PublicCursor(char kind, UUID revisionId, int next) {
    static final int MAX_PAGE = 100;

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((kind + "/" + revisionId + "/" + next).getBytes(StandardCharsets.US_ASCII));
    }

    /** @return the cursor, or null for no cursor; any malformed text is the 400 of the house schema */
    static PublicCursor decode(String encoded, char kind) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 96 || !encoded.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
            if (parts.length != 3 || parts[0].length() != 1 || parts[0].charAt(0) != kind || !parts[2].matches("0|[1-9][0-9]{0,5}")) {
                throw new InvalidRequestException();
            }
            PublicCursor cursor = new PublicCursor(kind, UuidPolicy.requireEntityId(UUID.fromString(parts[1]), "revisionId"),
                    Integer.parseInt(parts[2]));
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    static int pageSize(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new InvalidRequestException();
        int size = Integer.parseInt(value);
        if (size > MAX_PAGE) throw new InvalidRequestException();
        return size;
    }
}
