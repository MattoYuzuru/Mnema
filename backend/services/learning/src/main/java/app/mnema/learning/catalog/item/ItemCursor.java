package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Current-head page location; the bound deck revision prevents mixed-version Browse pages. */
record ItemCursor(UUID deckRevisionId, int nextOrdinal) {
    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (deckRevisionId + "/" + nextOrdinal).getBytes(StandardCharsets.US_ASCII));
    }

    static ItemCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 96 || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw new InvalidRequestException();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
            if (parts.length != 2 || !parts[1].matches("0|[1-9][0-9]{0,4}")) throw new InvalidRequestException();
            int ordinal = Integer.parseInt(parts[1]);
            if (ordinal >= 100_000) throw new InvalidRequestException();
            ItemCursor result = new ItemCursor(ItemIds.entity(parts[0]), ordinal);
            if (!result.encode().equals(encoded)) throw new InvalidRequestException();
            return result;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    static int pageSize(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new InvalidRequestException();
        int size = Integer.parseInt(value);
        if (size > 100) throw new InvalidRequestException();
        return size;
    }
}
