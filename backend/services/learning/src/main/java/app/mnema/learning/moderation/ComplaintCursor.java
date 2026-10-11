package app.mnema.learning.moderation;

import app.mnema.learning.platform.api.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** The opaque keyset position in the console queue: a time (the deadline of an OPEN list, the decision time of a DECIDED one) and a complaint id. */
record ComplaintCursor(Instant time, UUID id) {
    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 100;

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((time.getEpochSecond() + "." + time.getNano() + "/" + id).getBytes(StandardCharsets.US_ASCII));
    }

    /** @return the cursor, or null for none; anything malformed is the 400 of the house schema */
    static ComplaintCursor decode(String encoded) {
        if (encoded == null) return null;
        if (encoded.isEmpty() || encoded.length() > 120 || !encoded.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII).split("/", -1);
            String[] time = parts.length == 2 ? parts[0].split("\\.", -1) : null;
            if (time == null || time.length != 2 || !time[0].matches("-?[0-9]{1,12}") || !time[1].matches("[0-9]{1,9}")) throw new InvalidRequestException();
            ComplaintCursor cursor = new ComplaintCursor(Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])), UUID.fromString(parts[1]));
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw new InvalidRequestException();
        }
    }

    static int pageSize(String value) {
        if (value == null) return DEFAULT_PAGE;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new InvalidRequestException();
        int size = Integer.parseInt(value);
        if (size > MAX_PAGE) throw new InvalidRequestException();
        return size;
    }
}
