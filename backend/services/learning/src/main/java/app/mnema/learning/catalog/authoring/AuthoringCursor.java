package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/** Immutable creation-order keyset shared by account-scoped authoring lists. */
record AuthoringCursor(Instant createdAt, UUID id) {
    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (createdAt + "/" + id).getBytes(StandardCharsets.US_ASCII));
    }

    static AuthoringCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 128 || !encoded.matches("[A-Za-z0-9_-]+")) {
            throw new InvalidRequestException();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
            String[] parts = value.split("/", -1);
            if (parts.length != 2) throw new InvalidRequestException();
            Instant time = Instant.parse(parts[0]);
            if (time.getNano() % 1000 != 0 || time.isBefore(Instant.EPOCH)
                    || time.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw new InvalidRequestException();
            AuthoringCursor result = new AuthoringCursor(time, AuthoringIds.entity(parts[1]));
            if (!result.encode().equals(encoded)) throw new InvalidRequestException();
            return result;
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidRequestException();
        }
    }

    static int pageSize(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new InvalidRequestException();
        int result = Integer.parseInt(value);
        if (result > 100) throw new InvalidRequestException();
        return result;
    }
}
