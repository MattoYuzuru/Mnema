package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/** Keyset location, not an authorization capability or a snapshot token. */
record DeckCursor(Instant createdAt, UUID deckId) {
    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (createdAt.toString() + "/" + deckId).getBytes(StandardCharsets.US_ASCII));
    }

    static DeckCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 128 || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw new InvalidRequestException();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            String[] parts = value.split("/", -1);
            if (parts.length != 2) throw new InvalidRequestException();
            Instant timestamp = Instant.parse(parts[0]);
            if (timestamp.getNano() % 1000 != 0 || timestamp.isBefore(Instant.EPOCH)
                    || timestamp.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw new InvalidRequestException();
            DeckCursor cursor = new DeckCursor(timestamp, DeckCommand.entityId(parts[1]));
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException | DateTimeParseException exception) {
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
